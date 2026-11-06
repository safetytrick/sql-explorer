import {AfterViewInit, Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild} from '@angular/core';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {FormsModule} from '@angular/forms';
import {forkJoin, map, of, skip, Subscription, switchMap} from 'rxjs';
import {GraphV2, SchemaLensService} from '../schema-lens.service';
import {EnvironmentService} from '../environment.service';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ThemeService} from '../theme.service';
import {decodeGraphParams, DEFAULT_LAYERS, DEFAULT_LAYOUT, DEPTHS, GraphLayers, GraphLayout, graphOwnedParamPatch} from './graph-url';
import {neighbourhood} from './neighbourhood';
import {buildAggregates} from './aggregates';
import {GridNode, GridSpacing, layoutGrid, LayoutBox, Slot} from './grid-layout';
import {Point, routeEdges} from './routing';
import {LayoutChange, LayoutItem, LayoutKind, planMove, SavedLayout, slotAt} from './layout-moves';
import {GraphLayoutService} from './graph-layout.service';
import {tableKey} from '../query-scope.service';
import {buildSearchRequest, decodeSearchParams, encodeSearchParams, resolveSchemaTagScope, UNCONSTRAINED_TAG_SCOPE} from '../search-url';
import {scopeOf} from '../scope-url';
import {ENV_PARAM} from '../environment-url';
import {schemaTarget} from '../tag-path';

const NW = 130;
const NH = 44;

/** The minimap canvas caches its last draw transform for pointer hit-testing. */
interface MinimapCanvas extends HTMLCanvasElement {
  _msc?: number;   // minimap scale
  _offX?: number;
  _offY?: number;
}

interface LayoutNode {
  id: string;          // schema.table
  schema: string;
  table: string;
  columnCount: number | null;
  queryCount: number;
  warn: boolean;
  selfJoin: boolean;
  view: boolean;
  x: number;
  y: number;
}

interface Edge {
  from: LayoutNode; to: LayoutNode; label: string; warn?: boolean; execs?: number;
  /** An FK no query joins on: it only enforces integrity (sqlx-aoy.3). */
  neverJoined?: boolean;
}

/** Dash patterns, in screen pixels before scaling. */
const DASHED = [5, 4];
const DOTTED = [1.5, 4];

/** The area one schema's tables occupy, and the colour it is drawn in. */
interface SchemaRegion {
  schema: string;
  color: string;
  /** Where the layout pulls this schema's nodes; fixed before settling. */
  cx: number;
  cy: number;
  /** Bounds measured from the settled nodes, so the box fits what is in it. */
  x: number;
  y: number;
  w: number;
  h: number;
}

/** Padding between a region's outermost node and its drawn edge. */
const REGION_PAD = 46;

/** An aggregate's drawn block on the grid layout: its root and members, outlined in its hue. */
interface AggregateBox { schema: string; root: string; hue: number; x: number; y: number; w: number; h: number; }

/** What a grid box is, for turning a drop into a saved change. */
interface GridItem { kind: LayoutKind; schema: string; name: string; box: LayoutBox; parent: LayoutBox | null; }

/** A move in progress on the grid: what is held, where it was picked up and where it is now, in world units. */
interface GridDrag { key: string; box: LayoutBox; startX: number; startY: number; x: number; y: number; active: boolean; }

/** Height of an aggregate's label strip, which is where an aggregate is picked up; matches GRID_SPACING. */
const AGGREGATE_HEADER = 18;

/** A table's aggregate on the grid: its hue, and whether the table is the root. */
interface Membership { hue: number; root: boolean; }

/**
 * An aggregate's hue, by its place in the sorted aggregate list. Successive
 * golden-angle steps keep neighbouring aggregates far apart on the wheel
 * however many there are, where a fixed palette would run out and repeat.
 */
function aggregateHue(index: number): number {
  return (200 + index * 137.508) % 360;
}

/**
 * Grid spacing by depth (sqlx-aoy.13): between schemas, then a schema's
 * padding and the gap between its cells, then an aggregate's padding, its
 * label strip and the gap between its tables. The gap between schemas leaves
 * room for the schema label drawn above each region.
 */
const GRID_SPACING: GridSpacing[] = [
  { gap: 80, pad: 0, header: 0 },
  { gap: 30, pad: 30, header: 0 },
  { gap: 14, pad: 12, header: 18 }
];

@Component({
  selector: 'app-graph-lens',
  templateUrl: './graph-lens.component.html',
  styleUrls: ['./graph-lens.component.scss'],
  imports: [FormsModule, RouterLink]
})
export class GraphLensComponent implements OnInit, AfterViewInit, OnDestroy {

  @ViewChild('graphCanvas') graphCanvas!: ElementRef<HTMLCanvasElement>;
  @ViewChild('minimapCanvas') minimapCanvas!: ElementRef<HTMLCanvasElement>;
  @ViewChild('wrap') wrap!: ElementRef<HTMLDivElement>;
  @ViewChild('tooltip') tooltip!: ElementRef<HTMLDivElement>;

  selectedTable: string | null = null;
  layers: GraphLayers = { ...DEFAULT_LAYERS };
  /** Nested grids (the default) or the force simulation, `var-layout=force` (sqlx-aoy.13). */
  layout: GraphLayout = DEFAULT_LAYOUT;
  /** The graph last laid out, so switching layout re-places it without a fetch. */
  private lastGraph: GraphV2 | null = null;
  private aggregateBoxes: AggregateBox[] = [];
  /** The grid's boxes, which the routes run between. */
  private gridBoxes: LayoutBox[] = [];
  /** Each table's aggregate, for tables in one of more than one table. */
  private membership = new Map<LayoutNode, Membership>();
  /** Routes by the layer set they were found for; routing is per visible set, so lanes leave no gaps. */
  private routeCache = new Map<string, Map<Edge, Point[]>>();
  /** The line under the pointer, highlighted end to end. */
  private hoveredEdge: Edge | null = null;
  /** The hand arrangement, shared by everyone (sqlx-aoy.15); empty until loaded. */
  private savedLayout = new SavedLayout();
  /** Every grid box by key, with what it is and the box it sits in. */
  private gridIndex = new Map<string, GridItem>();
  private gridDrag: GridDrag | null = null;
  /** Why the last move could not be saved; the layout is reloaded when this is set. */
  layoutError: string | null = null;
  /** Whether the theme is dark, which sets how light the aggregate hues are. */
  private darkTheme = true;
  loading = false;

  /**
   * Every table of the scoped schemas, not only the queried ones;
   * `var-tables=all` (sqlx-aoy.16).
   */
  allTables = false;
  /** Only the selected table's neighbourhood, this many hops out; `var-depth` (sqlx-aoy.9). */
  depth: number | null = null;
  readonly depths = DEPTHS;
  /** What the drawn neighbourhood was cut for, so a change to it re-lays the graph out. */
  private focusedFor = '';

  /** Scope of the last fetch — lets URL writes for selection/layers redraw without refetching. */
  private loadedScope: string | null = null;

  private nodes: LayoutNode[] = [];
  private fkEdges: Edge[] = [];
  private coEdges: Edge[] = [];
  private joinEdges: Edge[] = [];
  private viewEdges: Edge[] = [];
  private tx = 0;
  private ty = 0;
  private scale = 1;
  private dragging = false;
  private lastX = 0;
  private lastY = 0;
  private hovered: LayoutNode | null = null;
  private viewReady = false;
  private subscriptions = new Subscription();
  /** Canvas colors resolved from the active theme's CSS variables. */
  private palette = { blue: '#4a9eff', amber: '#f5a623', purple: '#b48cff', text: '#e8eaf0', text2: '#8b93a8',
    bg: '#0f1117', bg2: '#161b27', bg3: '#1e2535', border2: 'rgba(255,255,255,0.14)',
    green: '#3dd68c', teal: '#2dd4bf', red: '#f56565' };

  /** Settled bounds and colour per schema, drawn behind the nodes. */
  private regions: SchemaRegion[] = [];

  constructor(private schemaLensService: SchemaLensService,
              private environmentService: EnvironmentService,
              private analyzedSqlService: AnalyzedSqlService,
              private themeService: ThemeService,
              private route: ActivatedRoute,
              private router: Router,
              private graphLayoutService: GraphLayoutService) { }

  ngOnInit() {
    this.refreshPalette();
    this.loadArrangement();
    this.subscriptions.add(this.themeService.current$.subscribe(() => {
      this.refreshPalette();
      this.draw();
    }));
    this.subscriptions.add(this.route.queryParamMap.subscribe(() => this.applyRoute()));
    // a new environment is a new corpus under the same search
    this.subscriptions.add(this.environmentService.current$.pipe(skip(1)).subscribe(() => this.loadGraph()));
  }

  /** The legend's full key is showing; `var-legend=1` (sqlx-aoy.2). */
  legendOpen = false;

  /** The list of aggregates chosen by hand is showing; `var-overrides=1`. */
  overridesOpen = false;

  toggleOverrides() {
    this.router.navigate([], { relativeTo: this.route, queryParams: { 'var-overrides': this.overridesOpen ? null : '1' },
      queryParamsHandling: 'merge', replaceUrl: true });
  }

  /**
   * Every table whose aggregate was chosen by hand, in every schema: the
   * arrangement is shared and keyed by schema name, so this is everyone's,
   * not only what the current search draws. Sorted by schema, then table.
   */
  get overrides(): LayoutItem[] {
    return this.savedLayout.all()
      .filter(item => item.kind === 'table' && item.override)
      .sort((a, b) => a.schema.localeCompare(b.schema) || a.name.localeCompare(b.name));
  }

  /** Whether a table is drawn, so the list can say which overrides the graph is showing. */
  isDrawn(item: LayoutItem): boolean {
    return this.nodes.some(node => node.schema.toLowerCase() === item.schema && node.table.toLowerCase() === item.name);
  }

  /**
   * Drop one table's hand-chosen aggregate: it goes back to the one the
   * naming rule infers. Its position is kept. Shared, like every move, and
   * undone the same way, by dragging it back.
   */
  clearOverride(item: LayoutItem) {
    this.arrange([{ kind: 'table', schema: item.schema, name: item.name, inferred: true }]);
  }

  toggleLegend() {
    this.router.navigate([], { relativeTo: this.route, queryParams: { 'var-legend': this.legendOpen ? null : '1' },
      queryParamsHandling: 'merge', replaceUrl: true });
  }

  /**
   * The graph is a view of the workbench (sqlx-aoy.16): the search params are
   * what it shows, and the `var-` params how. Only a change to what it shows
   * refetches; selection and layer changes, including its own replaceUrl
   * writes and browser back/forward, redraw.
   */
  private applyRoute() {
    const state = decodeGraphParams(this.route.snapshot.queryParamMap);
    this.legendOpen = this.route.snapshot.queryParamMap.get('var-legend') === '1';
    this.overridesOpen = this.route.snapshot.queryParamMap.get('var-overrides') === '1';
    this.selectedTable = state.table;
    this.layers = state.layers;
    this.allTables = state.allTables ?? false;
    this.depth = state.depth ?? null;
    const layoutChanged = (state.layout ?? DEFAULT_LAYOUT) !== this.layout;
    this.layout = state.layout ?? DEFAULT_LAYOUT;
    if (this.scopeKey() === this.loadedScope) {
      // a new neighbourhood is a new set of tables, so it is laid out afresh
      if ((layoutChanged || this.focusKey() !== this.focusedFor) && this.lastGraph) {
        this.buildLayout(this.lastGraph);
        this.resetView();
      } else {
        this.draw();
      }
    } else {
      this.loadGraph();
    }
  }

  /** The schemas that got a region, in drawn order. */
  get regionSchemas(): string[] {
    return this.regions.map(region => region.schema);
  }

  /** Region boxes, for asserting they contain their nodes and stay apart. */
  get regionsForTest(): readonly SchemaRegion[] {
    return this.regions;
  }

  /** Drawn node count — the observable result of scoping, for tests. */
  get nodeCount(): number {
    return this.nodes.length;
  }

  /** @see scopeKey — exposed so a spec can assert a scope change is detected. */
  get scopeKeyForTest(): string {
    return this.scopeKey();
  }

  /**
   * What the graph shows: the search, normalized through the codec so `var-`
   * params and param order cannot cause spurious refetches, and whether every
   * table of the scoped schemas is drawn.
   */
  private scopeKey(): string {
    const search = encodeSearchParams(decodeSearchParams(this.route.snapshot.queryParamMap));
    return `${this.allTables ? 'all' : 'queried'}|${JSON.stringify(search)}`;
  }

  /**
   * Reflect graph-owned state into the URL without a history entry. A merge
   * patch: the workbench's search params beside it must survive selection and
   * layer clicks.
   */
  private writeUrl() {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: graphOwnedParamPatch({ table: this.selectedTable, layers: this.layers, layout: this.layout,
        allTables: this.allTables, depth: this.depth }),
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }

  /** Show only the selected table's neighbourhood, this many hops out, or (null) everything. */
  setDepth(depth: number | null) {
    this.depth = depth;
    this.writeUrl();
  }

  /**
   * What the neighbourhood depends on: the table at its centre, how far out,
   * and which kinds of line it walks. Empty when the whole graph shows.
   */
  private focusKey(): string {
    if (!this.depth || !this.selectedTable) { return ''; }
    return `${this.selectedTable.toLowerCase()}|${this.depth}|${JSON.stringify(this.layers)}`;
  }

  /** Draw every table of the scoped schemas, or only the queried ones. */
  toggleAllTables() {
    this.allTables = !this.allTables;
    this.writeUrl();
  }

  /** Whether the search is scoped to schemas, which is what "all tables" fills in. */
  get scopedToSchemas(): boolean {
    const search = decodeSearchParams(this.route.snapshot.queryParamMap);
    return search.scope.schemas.length > 0 || search.schemaTagFilter.includeTags.length > 0;
  }

  ngAfterViewInit() {
    this.viewReady = true;
    this.resetView();
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
  }

  private loadGraph() {
    this.loadedScope = this.scopeKey();
    const state = decodeSearchParams(this.route.snapshot.queryParamMap);
    this.loading = true;
    this.tagScope$().pipe(switchMap(tagScope =>
      this.schemaLensService.getSearchGraph(buildSearchRequest(state, tagScope, { limit: null, offset: null }), this.allTables)
    )).subscribe({
      next: graph => {
        this.buildLayout(graph);
        this.loading = false;
        this.resetView();
      },
      error: () => this.loading = false
    });
  }

  /**
   * The schema-tag filter resolved against live tag data.
   *
   * <p>Shared by both load paths deliberately. `?schemaTagsExclude=` travels
   * across navigations (see `scope-url.ts`), so it can arrive on any graph —
   * and it has to mean here exactly what it means on `/queries`, or the same
   * link describes two different working sets. Resolving it in one place is
   * what keeps that true.
   *
   * <p>Exclusion reaches tagged tables as well as tagged schemas, which is why
   * the table half is resolved too.
   */
  private tagScope$() {
    const filter = decodeSearchParams(this.route.snapshot.queryParamMap).schemaTagFilter;
    if (!filter.includeTags.length && !filter.excludeTags.length) {
      return of(UNCONSTRAINED_TAG_SCOPE);
    }
    const tablesByTag$ = filter.excludeTags.length
      ? forkJoin(filter.excludeTags.map(tag => this.analyzedSqlService.getTablesByTag(tag))).pipe(
          map(perTag => new Map<string, string[]>(filter.excludeTags.map((tag, index) =>
            [tag, perTag[index].map(table => tableKey(table.schema, table.name))]))))
      : of(new Map<string, string[]>());
    return this.schemaLensService.getSchemaTree().pipe(switchMap(tree => {
      const names = tree.schemas.map(schema => schema.name.toLowerCase());
      const targets = tree.schemas.map(schema => schemaTarget(schema.name));
      return forkJoin({
        byPath: this.analyzedSqlService.getTagsForTargets(targets),
        tablesByTag: tablesByTag$
      }).pipe(map(({ byPath, tablesByTag }) => {
        // Zipped by index: the batched answer is positional (sqlx-3ai).
        const tagsBySchema = new Map<string, string[]>(tree.schemas.map((schema, index) =>
          [schema.name.toLowerCase(), (byPath[index] ?? []).map(tag => tag.name)]));
        return resolveSchemaTagScope(names, tagsBySchema, filter, tablesByTag);
      }));
    }));
  }

  // ── layout ──

  /**
   * Where each schema sits, before any node moves.
   *
   * <p>Regions are placed first and nodes settle <em>within</em> them, rather
   * than regions being inferred from a settled cloud. A background needs a
   * defined area to be drawn in, and deriving one from wherever the force
   * layout happened to leave things is guesswork — two schemas that drifted
   * together would produce overlapping boxes with no way to separate them.
   *
   * <p>Centres go on a circle sized by the largest region, so the spacing is
   * set by the schema with the most tables rather than by the average.
   */
  private placeRegions(schemas: string[], counts: Map<string, number>) {
    // How far a region reaches from its centre once its nodes have settled.
    const extent = (name: string) =>
      this.regionRadius(counts.get(name) ?? 1) * 0.7 + NW / 2 + REGION_PAD;
    // Centres sit on a circle, so the gap between neighbours is the chord
    // 2·R·sin(π/n). Solve that for R against the widest pair rather than
    // guessing a multiplier: with four populated schemas a guess put two of
    // them on top of each other, which makes the colours meaningless.
    const widestPair = schemas.length < 2 ? 0
      : Math.max(...schemas.map((name, index) =>
          extent(name) + extent(schemas[(index + 1) % schemas.length])));
    const ring = schemas.length < 2 ? 0
      : Math.max(320, widestPair / (2 * Math.sin(Math.PI / schemas.length)) * 1.15);
    this.regions = schemas.map((schema, index) => {
      const angle = index / schemas.length * Math.PI * 2 - Math.PI / 2;
      return {
        schema,
        color: this.regionColor(index),
        cx: 400 + ring * Math.cos(angle),
        cy: 300 + ring * Math.sin(angle),
        x: 0, y: 0, w: 0, h: 0
      };
    });
  }

  /** Grows with node count, not linearly — area is what has to hold them. */
  private regionRadius(count: number): number {
    return 120 + Math.sqrt(count) * 62;
  }

  /**
   * A schema's colour, by position in the sorted schema list.
   *
   * <p>Sorted so a schema keeps its colour between loads: assigning by
   * arrival order would recolour the whole graph whenever the query changed,
   * and a colour that moves carries no information.
   *
   * <p>Hues come from the theme tokens, so all four themes stay coherent.
   * More schemas than hues cycles rather than inventing colours — with the
   * region labelled, a repeated hue is a weaker signal, not a wrong one.
   */
  private regionColor(index: number): string {
    const hues = [this.palette.blue, this.palette.teal, this.palette.purple,
      this.palette.amber, this.palette.green, this.palette.red];
    return hues[index % hues.length];
  }

  private assignRegionColors() {
    this.regions.forEach((region, index) => region.color = this.regionColor(index));
  }

  /** Bounds of each region's settled nodes. Empty regions are dropped. */
  private measureRegions() {
    for (const region of this.regions) {
      const members = this.nodes.filter(node => node.schema === region.schema);
      if (!members.length) { region.w = 0; continue; }
      // node.x/y is the box's top-left — drawNode roundRects straight from it,
      // and edgePorts adds NW/2 to reach the centre
      const left = Math.min(...members.map(node => node.x));
      const right = Math.max(...members.map(node => node.x + NW));
      const top = Math.min(...members.map(node => node.y));
      const bottom = Math.max(...members.map(node => node.y + NH));
      region.x = left - REGION_PAD;
      region.y = top - REGION_PAD;
      region.w = (right - left) + REGION_PAD * 2;
      region.h = (bottom - top) + REGION_PAD * 2;
    }
    this.regions = this.regions.filter(region => region.w > 0);
  }

  /**
   * Push overlapping regions apart, moving their nodes with them.
   *
   * <p>{@link placeRegions} spaces the centres from a predicted extent, and a
   * prediction is all it can be: the settled radius of a cloud of mutually
   * repelling nodes is not something a formula gets right — a 24-table schema
   * overflowed its predicted box and sat on its neighbour. Measuring the boxes
   * and separating them afterwards needs no prediction at all.
   *
   * <p>Separation is along whichever axis needs the smaller push, so regions
   * slide the short way rather than being flung across the canvas, and both
   * sides move half so no schema is privileged. Bounded iterations because
   * this runs once per load and a pathological graph must not hang the tab.
   */
  private separateRegions() {
    const members = new Map(this.regions.map(region =>
      [region.schema, this.nodes.filter(node => node.schema === region.schema)]));
    const shift = (region: SchemaRegion, dx: number, dy: number) => {
      region.x += dx; region.y += dy; region.cx += dx; region.cy += dy;
      for (const node of members.get(region.schema) ?? []) { node.x += dx; node.y += dy; }
    };
    const gap = 24;
    for (let pass = 0; pass < 60; pass++) {
      let moved = false;
      for (let i = 0; i < this.regions.length; i++) {
        for (let j = i + 1; j < this.regions.length; j++) {
          const a = this.regions[i];
          const b = this.regions[j];
          const overlapX = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x) + gap;
          const overlapY = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y) + gap;
          if (overlapX <= 0 || overlapY <= 0) { continue; }
          moved = true;
          if (overlapX < overlapY) {
            const push = (overlapX / 2) * (a.x < b.x ? -1 : 1);
            shift(a, push, 0); shift(b, -push, 0);
          } else {
            const push = (overlapY / 2) * (a.y < b.y ? -1 : 1);
            shift(a, 0, push); shift(b, 0, -push);
          }
        }
      }
      if (!moved) { return; }
    }
  }

  private buildLayout(fetched: GraphV2) {
    this.lastGraph = fetched;
    this.focusedFor = this.focusKey();
    const graph = this.depth && this.selectedTable
      ? neighbourhood(fetched, this.selectedTable, this.depth, this.layers)
      : fetched;
    this.aggregateBoxes = [];
    this.gridBoxes = [];
    this.gridIndex.clear();
    this.membership.clear();
    this.routeCache.clear();
    this.hoveredEdge = null;
    const byId = new Map<string, LayoutNode>();
    // sorted, so a schema's colour and position do not move between loads
    const schemas = [...new Set(graph.nodes.map(node => node.schema))].sort();
    const counts = new Map<string, number>(
      schemas.map(name => [name, graph.nodes.filter(node => node.schema === name).length]));
    this.placeRegions(schemas, counts);
    const homeOf = new Map(this.regions.map(region => [region.schema, region]));

    this.nodes = graph.nodes.map((node, index) => {
      const home = homeOf.get(node.schema);
      const spread = this.regionRadius(counts.get(node.schema) ?? 1) * 0.7;
      const angle = index / Math.max(1, graph.nodes.length) * Math.PI * 2;
      const layoutNode: LayoutNode = {
        id: `${node.schema}.${node.table}`,
        schema: node.schema,
        table: node.table,
        columnCount: node.columnCount,
        queryCount: node.queryCount,
        warn: node.warn,
        selfJoin: node.selfJoin,
        view: node.view,
        // seeded inside its own region rather than on one global circle
        x: (home?.cx ?? 400) + spread * Math.cos(angle),
        y: (home?.cy ?? 300) + spread * Math.sin(angle)
      };
      byId.set(layoutNode.id.toLowerCase(), layoutNode);
      return layoutNode;
    });
    const resolve = (schema: string, table: string) => byId.get(`${schema}.${table}`.toLowerCase());
    this.fkEdges = graph.fkEdges
      .map(edge => ({ from: resolve(edge.fromSchema, edge.fromTable), to: resolve(edge.toSchema, edge.toTable),
        label: edge.label, warn: edge.mismatch, neverJoined: edge.joined === false }))
      .filter(edge => edge.from && edge.to) as Edge[];
    this.joinEdges = (graph.joinEdges || [])
      .map(edge => ({ from: resolve(edge.aSchema, edge.aTable), to: resolve(edge.bSchema, edge.bTable),
        label: edge.columns.join(', '), execs: edge.executions }))
      .filter(edge => edge.from && edge.to) as Edge[];
    // Every joined pair is also queried together. The co-query layer keeps
    // only the pairs that are not joined, so each pair is drawn once and a
    // dashed line means "used together, never joined" (sqlx-aoy.3).
    const pair = (a: LayoutNode, b: LayoutNode) => [a.id, b.id].sort().join('|');
    const joined = new Set(this.joinEdges.map(edge => pair(edge.from, edge.to)));
    this.coEdges = graph.coQueryEdges
      .map(edge => ({ from: resolve(edge.aSchema, edge.aTable), to: resolve(edge.bSchema, edge.bTable), label: '', execs: edge.executions }))
      .filter(edge => edge.from && edge.to && !joined.has(pair(edge.from, edge.to))) as Edge[];
    this.viewEdges = (graph.viewEdges || [])
      .map(edge => ({ from: resolve(edge.fromSchema, edge.fromTable), to: resolve(edge.toSchema, edge.toTable), label: 'over' }))
      .filter(edge => edge.from && edge.to) as Edge[];
    if (this.layout === 'grid') {
      this.placeOnGrid();
      return;
    }
    this.runForceLayout();
    this.measureRegions();
    this.separateRegions();
  }

  toggleLayout() {
    this.layout = this.layout === 'grid' ? 'force' : 'grid';
    if (this.lastGraph) {
      this.buildLayout(this.lastGraph);
      this.resetView();
    }
    this.writeUrl();
  }

  /**
   * Place every table on nested grids: schema, then aggregate, then table
   * (sqlx-aoy.13). Aggregates come from names plus foreign keys and joins
   * (aoy.12); a table in no aggregate sits in its schema's grid on its own.
   * Nothing here is saved yet, so every slot is auto-placed: aggregates
   * first, then lone tables, each in name order, roots at their block's
   * top-left (aoy.15 adds hand placement).
   */
  private placeOnGrid() {
    const relations = [...this.fkEdges, ...this.joinEdges].map(edge => ({
      a: { schema: edge.from.schema, table: edge.from.table },
      b: { schema: edge.to.schema, table: edge.to.table }
    }));
    const saved = this.savedLayout;
    const aggregates = buildAggregates(
      this.nodes.map(node => ({ schema: node.schema, table: node.table })), relations, saved.overrides());
    const items = new Map<string, Omit<GridItem, 'box' | 'parent'>>();
    const leaf = (schema: string, table: string, fallback?: Slot): GridNode => {
      const key = `t:${schema}.${table}`.toLowerCase();
      items.set(key, { kind: 'table', schema, name: table });
      return { key, size: { w: NW, h: NH }, slot: saved.slot('table', schema, table) ?? fallback };
    };
    const schemas = [...new Set(this.nodes.map(node => node.schema))].sort();
    const tree: GridNode[] = schemas.map(schema => {
      items.set(`s:${schema}`, { kind: 'schema', schema, name: '' });
      return {
        key: `s:${schema}`,
        slot: saved.slot('schema', schema, ''),
        children: aggregates.filter(aggregate => aggregate.schema === schema).map(aggregate => {
          if (aggregate.tables.length === 1) {
            // keyed so lone tables sort after the aggregates in their schema
            return leaf(schema, aggregate.root);
          }
          const key = `a:${schema}.${aggregate.root}`.toLowerCase();
          items.set(key, { kind: 'aggregate', schema, name: aggregate.root });
          return {
            key,
            slot: saved.slot('aggregate', schema, aggregate.root),
            // unarranged, the root takes its block's top-left
            children: aggregate.tables.map((table, index) =>
              leaf(schema, table, index === 0 ? { col: 0, row: 0 } : undefined))
          };
        })
      };
    });

    const nodeAt = new Map(this.nodes.map(node => [`t:${node.id}`.toLowerCase(), node]));
    const blocks = aggregates.filter(aggregate => aggregate.tables.length > 1);
    const blockOf = new Map(blocks.map((aggregate, index) =>
      [`a:${aggregate.schema}.${aggregate.root}`.toLowerCase(), { root: aggregate.root, hue: aggregateHue(index) }]));
    blocks.forEach((aggregate, index) => aggregate.tables.forEach(table => {
      const node = nodeAt.get(`t:${aggregate.schema}.${table}`.toLowerCase());
      if (node) { this.membership.set(node, { hue: aggregateHue(index), root: table === aggregate.root }); }
    }));
    const regionOf = new Map(this.regions.map(region => [region.schema, region]));
    const place = (box: { key: string; x: number; y: number }) => {
      const node = nodeAt.get(box.key);
      if (node) { node.x = box.x; node.y = box.y; }
    };
    this.gridBoxes = layoutGrid(tree, GRID_SPACING);
    const index = (boxes: LayoutBox[], parent: LayoutBox | null) => {
      for (const box of boxes) {
        const item = items.get(box.key);
        if (item) { this.gridIndex.set(box.key, { ...item, box, parent }); }
        index(box.children, box);
      }
    };
    index(this.gridBoxes, null);
    for (const schemaBox of this.gridBoxes) {
      const region = regionOf.get(schemaBox.key.slice(2));
      if (region) {
        Object.assign(region, { x: schemaBox.x, y: schemaBox.y, w: schemaBox.w, h: schemaBox.h,
          cx: schemaBox.x + schemaBox.w / 2, cy: schemaBox.y + schemaBox.h / 2 });
      }
      for (const cell of schemaBox.children) {
        place(cell);
        if (cell.key.startsWith('a:')) {
          const block = blockOf.get(cell.key);
          this.aggregateBoxes.push({ schema: schemaBox.key.slice(2), root: block?.root ?? '', hue: block?.hue ?? 0,
            x: cell.x, y: cell.y, w: cell.w, h: cell.h });
          cell.children.forEach(place);
        }
      }
    }
  }

  /**
   * The routed path of every visible line, on the grid layout (sqlx-aoy.14);
   * null on the force layout, where lines stay straight.
   *
   * <p>Routed per set of visible layers and cached, so toggling a layer
   * re-routes once and a redraw never does. Routing what is visible, rather
   * than everything, keeps hidden lines from holding lanes open. Foreign keys
   * go first and then the busiest joins, since earlier routes pull later ones
   * onto their channels.
   */
  private currentRoutes(): Map<Edge, Point[]> | null {
    if (this.layout !== 'grid' || !this.gridBoxes.length) { return null; }
    const key = JSON.stringify(this.layers);
    let routes = this.routeCache.get(key);
    if (!routes) {
      const busiest = (edges: Edge[]) => [...edges].sort((a, b) => (b.execs ?? 0) - (a.execs ?? 0));
      const edges = [
        ...(this.layers.fk ? this.fkEdges : []),
        ...(this.layers.join ? busiest(this.joinEdges) : []),
        ...(this.layers.view ? this.viewEdges : []),
        ...(this.layers.co ? busiest(this.coEdges) : [])
      ];
      const leaf = (node: LayoutNode) => `t:${node.id}`.toLowerCase();
      const found = routeEdges(this.gridBoxes,
        edges.map((edge, index) => ({ id: String(index), from: leaf(edge.from), to: leaf(edge.to) })));
      routes = new Map<Edge, Point[]>();
      edges.forEach((edge, index) => {
        const path = found.get(String(index));
        if (path) { routes!.set(edge, path); }
      });
      this.routeCache.set(key, routes);
    }
    return routes;
  }

  /** Routed paths, for asserting the grid draws lines through its gutters. */
  routesForTest(): ReadonlyMap<Edge, Point[]> | null {
    return this.currentRoutes();
  }

  /** The line as drawn: its route on the grid, else straight between the tables' borders. */
  private pathOf(edge: Edge, routes: Map<Edge, Point[]> | null): Point[] {
    const routed = routes?.get(edge);
    if (routed) { return routed; }
    const [x1, y1, x2, y2] = this.edgePorts(edge.from, edge.to);
    return [{ x: x1, y: y1 }, { x: x2, y: y2 }];
  }

  /**
   * The lines to bring forward: the one under the pointer, or every line of
   * the table under the pointer or else the selected one. Null when nothing
   * is in focus, and every line draws as usual.
   */
  private focusEdges(): Set<Edge> | null {
    if (this.hoveredEdge) { return new Set([this.hoveredEdge]); }
    const node = this.hovered ?? this.selectedNode();
    if (!node) { return null; }
    const touches = (edge: Edge) => edge.from === node || edge.to === node;
    return new Set([...this.fkEdges, ...this.joinEdges, ...this.coEdges, ...this.viewEdges].filter(touches));
  }

  /**
   * A line's colour on the grid: the aggregate of its child end
   * (sqlx-aoy.14). A foreign key's child is the table holding the key; for a
   * join or a co-query, which have no direction, it is the end that is a
   * member rather than a root. A line touching no aggregate is grey.
   */
  private aggregateColor(edge: Edge, directed: boolean, alpha: number): string {
    const from = this.membership.get(edge.from);
    const to = this.membership.get(edge.to);
    const owner = directed ? from ?? to
      : from && !from.root ? from : to && !to.root ? to : from ?? to;
    return owner ? this.hueColor(owner.hue, alpha) : this.alpha(this.palette.text2, alpha);
  }

  private hueColor(hue: number, alpha: number): string {
    return `hsla(${hue.toFixed(1)}, 65%, ${this.darkTheme ? 62 : 40}%, ${alpha})`;
  }

  // ── arranging the grid by hand (sqlx-aoy.15) ──

  private loadArrangement() {
    this.graphLayoutService.load().subscribe({
      next: items => {
        this.savedLayout = new SavedLayout(items);
        this.relayoutGrid(true);
      },
      // nothing saved, or the store is unreachable: placement is automatic
      error: () => undefined
    });
  }

  /** Lay the loaded graph out again on the grid, after the arrangement changed. */
  private relayoutGrid(fit: boolean) {
    if (this.layout !== 'grid' || !this.lastGraph) { return; }
    this.buildLayout(this.lastGraph);
    if (fit) { this.resetView(); } else { this.draw(); }
  }

  /**
   * What a press on the grid picks up: a table anywhere on it, an aggregate by
   * its label strip, a schema by its name above the region. Anything else is
   * a pan, so the canvas still drags the way it always has.
   */
  private grabAt(x: number, y: number): GridItem | null {
    if (this.layout !== 'grid') { return null; }
    const node = this.nodes.find(candidate =>
      x >= candidate.x && x <= candidate.x + NW && y >= candidate.y && y <= candidate.y + NH);
    if (node) { return this.gridIndex.get(`t:${node.id}`.toLowerCase()) ?? null; }
    for (const item of this.gridIndex.values()) {
      const box = item.box;
      const across = x >= box.x && x <= box.x + box.w;
      if (item.kind === 'aggregate' && across && y >= box.y && y <= box.y + AGGREGATE_HEADER) { return item; }
      // the name is drawn above the region, and only when there is more than one
      if (item.kind === 'schema' && this.regions.length > 1 && across && y >= box.y - 22 && y <= box.y + 4) {
        return item;
      }
    }
    return null;
  }

  /**
   * The saved changes a drop makes, or null when it makes none.
   *
   * <p>A schema or an aggregate moves within its grid. A table moves within
   * its aggregate; dropped into another aggregate it joins it, dropped on a
   * table that is in no aggregate the two make a new one, and dropped on the
   * open schema it leaves its aggregate. A root carries its aggregate with it,
   * since moving it out on its own would leave the members under nothing.
   * Nothing crosses schemas: an aggregate never spans two.
   */
  private dropChanges(key: string, x: number, y: number): LayoutChange[] | null {
    const item = this.gridIndex.get(key);
    if (!item) { return null; }
    const inside = (box: LayoutBox) => x >= box.x && x <= box.x + box.w && y >= box.y && y <= box.y + box.h;
    if (item.kind === 'schema') { return this.moveWithin(this.gridBoxes, key, x, y); }
    const schemaBox = this.gridBoxes.find(inside);
    if (!schemaBox || this.gridIndex.get(schemaBox.key)?.schema !== item.schema) { return null; }
    if (item.kind === 'aggregate') { return this.moveWithin(schemaBox.children, key, x, y); }

    const container = item.parent;
    const aggregateBox = schemaBox.children.find(box => box.key.startsWith('a:') && inside(box));
    if (aggregateBox) {
      if (aggregateBox === container) { return this.moveWithin(aggregateBox.children, key, x, y); }
      const root = this.gridIndex.get(aggregateBox.key)!.name;
      return [{ kind: 'table', schema: item.schema, name: item.name, parent: root },
        ...this.moveWithin(aggregateBox.children, key, x, y)];
    }
    const lone = schemaBox.children.find(box => box.key.startsWith('t:') && box.key !== key && inside(box));
    if (lone) {
      // a new aggregate, in the lone table's cell, rooted at it
      const root = this.gridIndex.get(lone.key)!;
      return [
        { kind: 'table', schema: item.schema, name: item.name, parent: root.name, slot: { col: 1, row: 0 } },
        { kind: 'table', schema: item.schema, name: root.name, slot: { col: 0, row: 0 } },
        { kind: 'aggregate', schema: item.schema, name: root.name, slot: lone.slot }
      ];
    }
    if (container === schemaBox) { return this.moveWithin(schemaBox.children, key, x, y); }
    const node = this.nodes.find(candidate => `t:${candidate.id}`.toLowerCase() === key);
    if (node && this.membership.get(node)?.root && container) {
      return this.moveWithin(schemaBox.children, container.key, x, y);
    }
    return [{ kind: 'table', schema: item.schema, name: item.name, parent: null },
      ...this.moveWithin(schemaBox.children, key, x, y)];
  }

  /** Slots for every cell of a grid after `key` is dropped at (x, y) in it. */
  private moveWithin(cells: LayoutBox[], key: string, x: number, y: number): LayoutChange[] {
    const own = cells.find(cell => cell.key === key);
    const plan = planMove(cells.map(cell => ({ key: cell.key, slot: cell.slot })), key, slotAt(cells, x, y),
      own ? own.slot : null);
    const changes: LayoutChange[] = [];
    for (const [cellKey, slot] of plan) {
      const item = this.gridIndex.get(cellKey);
      if (item) { changes.push({ kind: item.kind, schema: item.schema, name: item.name, slot }); }
    }
    return changes;
  }

  /** Apply changes here at once, then save them; a failed save says so and reloads what is saved. */
  private arrange(changes: LayoutChange[]) {
    const { layout, written } = this.savedLayout.apply(changes);
    this.savedLayout = layout;
    this.layoutError = null;
    this.relayoutGrid(false);
    this.graphLayoutService.save(written).subscribe({
      next: items => this.savedLayout = new SavedLayout(items),
      error: error => {
        this.layoutError = `the move was not saved: ${error?.error?.message ?? error?.message ?? 'no answer'}`;
        this.loadArrangement();
      }
    });
  }

  /** Back to automatic placement for the schemas shown. Shared, so it asks first. */
  resetArrangement() {
    const schemas = [...new Set(this.nodes.map(node => node.schema))].sort();
    if (!schemas.length || !window.confirm(
      `Put the tables and aggregates of ${schemas.join(', ')} back where they are placed automatically? `
      + 'Tables moved into or out of an aggregate stay there; only positions are reset, '
      + 'including tables the current search hides. The arrangement is shared, so this is for everyone.')) {
      return;
    }
    forkJoin(schemas.map(schema => this.graphLayoutService.reset(schema))).subscribe({
      next: () => this.loadArrangement(),
      error: () => {
        this.layoutError = 'the arrangement could not be reset';
        this.loadArrangement();
      }
    });
  }

  /** Box keys by kind, for asserting what the grid indexed. */
  gridItemsForTest(): {key: string; kind: LayoutKind; slot: Slot}[] {
    return [...this.gridIndex.entries()].map(([key, item]) => ({ key, kind: item.kind, slot: item.box.slot }));
  }

  private runForceLayout() {
    const springs = [...this.fkEdges, ...this.joinEdges, ...this.coEdges, ...this.viewEdges];
    const homes = new Map(this.regions.map(region => [region.schema, region]));
    for (let iteration = 0; iteration < 220; iteration++) {
      const cooling = 1 - iteration / 220;
      // pairwise repulsion
      for (let i = 0; i < this.nodes.length; i++) {
        for (let j = i + 1; j < this.nodes.length; j++) {
          const a = this.nodes[i];
          const b = this.nodes[j];
          let dx = a.x - b.x;
          let dy = a.y - b.y;
          const distSq = Math.max(400, dx * dx + dy * dy);
          const force = 220000 / distSq * cooling;
          const dist = Math.sqrt(distSq);
          dx /= dist; dy /= dist;
          a.x += dx * force; a.y += dy * force;
          b.x -= dx * force; b.y -= dy * force;
        }
      }
      // Springs. A join that crosses schemas pulls at a fraction of the
      // strength, because the grouping is now the primary structure and a
      // handful of cross-schema foreign keys would otherwise haul two regions
      // into each other — which is exactly what a dense four-schema graph did.
      // The connection is not lost: it is still drawn, and now visibly as a
      // line leaving one area for another, which is the point.
      for (const edge of springs) {
        const dx = edge.to.x - edge.from.x;
        const dy = edge.to.y - edge.from.y;
        const dist = Math.max(1, Math.sqrt(dx * dx + dy * dy));
        const pull = edge.from.schema === edge.to.schema ? 0.02 : 0.002;
        const force = (dist - 230) * pull * cooling;
        edge.from.x += dx / dist * force; edge.from.y += dy / dist * force;
        edge.to.x -= dx / dist * force; edge.to.y -= dy / dist * force;
      }
      // Gravity toward the node's own schema, not the canvas centre. This is
      // what keeps a schema together: repulsion and cross-schema springs would
      // otherwise blend everything into one cloud, and a region measured from
      // that cloud is a box around nothing in particular.
      //
      // Stronger than the global pull it replaces (0.004) because it now has
      // to win against cross-schema springs. Not so strong that it freezes the
      // layout — related tables still draw together inside a region, and two
      // schemas that join heavily still lean toward each other.
      for (const node of this.nodes) {
        const home = homes.get(node.schema);
        const cx = home ? home.cx : 400;
        const cy = home ? home.cy : 300;
        node.x += (cx - node.x) * 0.022 * cooling;
        node.y += (cy - node.y) * 0.022 * cooling;
      }
    }
  }

  // ── view transforms ──

  private wx(x: number) { return x * this.scale + this.tx; }
  private wy(y: number) { return y * this.scale + this.ty; }
  private worldX(sx: number) { return (sx - this.tx) / this.scale; }
  private worldY(sy: number) { return (sy - this.ty) / this.scale; }

  resetView() {
    if (!this.viewReady) { return; }
    const canvas = this.graphCanvas.nativeElement;
    canvas.width = this.wrap.nativeElement.clientWidth;
    canvas.height = this.wrap.nativeElement.clientHeight;
    if (!this.nodes.length) { this.draw(); return; }
    const xs = this.nodes.map(node => node.x);
    const ys = this.nodes.map(node => node.y);
    let minX = Math.min(...xs) - 40;
    let maxX = Math.max(...xs) + NW + 40;
    let minY = Math.min(...ys) - 40;
    let maxY = Math.max(...ys) + NH + 40;
    // Regions extend past their nodes by REGION_PAD, and their labels sit
    // above the top edge — fitting to nodes alone clips both.
    for (const region of this.regions) {
      minX = Math.min(minX, region.x - 10);
      maxX = Math.max(maxX, region.x + region.w + 10);
      minY = Math.min(minY, region.y - 24);
      maxY = Math.max(maxY, region.y + region.h + 10);
    }
    this.scale = Math.min(canvas.width / (maxX - minX), canvas.height / (maxY - minY), 1.4) * 0.92;
    this.tx = (canvas.width - (maxX - minX) * this.scale) / 2 - minX * this.scale;
    this.ty = (canvas.height - (maxY - minY) * this.scale) / 2 - minY * this.scale;
    this.draw();
  }

  zoom(factor: number) {
    const canvas = this.graphCanvas.nativeElement;
    const cx = canvas.width / 2;
    const cy = canvas.height / 2;
    this.tx = cx - (cx - this.tx) * factor;
    this.ty = cy - (cy - this.ty) * factor;
    this.scale *= factor;
    this.draw();
  }

  toggle(layer: keyof GraphLayers) {
    this.layers = { ...this.layers, [layer]: !this.layers[layer] };
    this.draw();
    this.writeUrl();
  }

  hasViewEdges(): boolean {
    return this.viewEdges.length > 0;
  }

  @HostListener('window:resize')
  onResize() { this.resetView(); }

  // ── interactions ──

  onMouseDown(event: MouseEvent) {
    this.downX = event.clientX;
    this.downY = event.clientY;
    const rect = this.graphCanvas.nativeElement.getBoundingClientRect();
    const x = this.worldX(event.clientX - rect.left);
    const y = this.worldY(event.clientY - rect.top);
    const held = this.grabAt(x, y);
    if (held) {
      // becomes a move once it travels; until then it may still be a click
      const key = [...this.gridIndex.entries()].find(([, item]) => item === held)![0];
      this.gridDrag = { key, box: held.box, startX: x, startY: y, x, y, active: false };
      return;
    }
    this.dragging = true;
    this.lastX = event.clientX;
    this.lastY = event.clientY;
    this.downX = event.clientX;
    this.downY = event.clientY;
  }

  /** Where the last press started: a click that ends a pan is not a click on what it ends over. */
  private downX = 0;
  private downY = 0;

  private wasDrag(event: MouseEvent): boolean {
    return Math.hypot(event.clientX - this.downX, event.clientY - this.downY) > 4;
  }

  /**
   * The drawn edge nearest the pointer, within a few screen pixels (sqlx-aoy.4).
   * A line is a polyline: straight between the borders on the force layout,
   * its route on the grid. Only layers on screen are hit.
   */
  private edgeAt(event: MouseEvent): Edge | null {
    const rect = this.graphCanvas.nativeElement.getBoundingClientRect();
    const px = this.worldX(event.clientX - rect.left);
    const py = this.worldY(event.clientY - rect.top);
    const tolerance = 6 / this.scale;
    const candidates = [
      ...(this.layers.fk ? this.fkEdges : []),
      ...(this.layers.join ? this.joinEdges : []),
      ...(this.layers.co ? this.coEdges : []),
      ...(this.layers.view ? this.viewEdges : [])
    ];
    const routes = this.currentRoutes();
    let best: Edge | null = null;
    let bestDistance = tolerance;
    for (const edge of candidates) {
      const path = this.pathOf(edge, routes);
      for (let i = 1; i < path.length; i++) {
        const { x: x1, y: y1 } = path[i - 1];
        const dx = path[i].x - x1;
        const dy = path[i].y - y1;
        const length = dx * dx + dy * dy;
        const t = length === 0 ? 0 : Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / length));
        const distance = Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
        if (distance <= bestDistance) {
          best = edge;
          bestDistance = distance;
        }
      }
    }
    return best;
  }

  /**
   * The search this graph was drawn from, as workbench params, less its table
   * scope: what a link out of the graph keeps, so the queries it opens are the
   * ones the graph counted (sqlx-aoy.8). The environment is kept too. The
   * graph's own params and `view` are not: a link opens the list.
   */
  private carriedSearch(): Record<string, string> {
    const search = encodeSearchParams(decodeSearchParams(this.route.snapshot.queryParamMap));
    delete search['tables'];
    delete search['tableMode'];
    const env = this.route.snapshot.queryParams[ENV_PARAM];
    return {...search, ...(env ? {[ENV_PARAM]: env} : {})};
  }

  /**
   * Whether the graph is drawn from a filtered search, so what it says about
   * queries is about the matching ones: a key "no query joins on" may well be
   * joined by a query the search leaves out.
   */
  get filtered(): boolean {
    return Object.keys(encodeSearchParams(decodeSearchParams(this.route.snapshot.queryParamMap))).length > 0;
  }

  /** The matching queries that touch both ends of an edge, in the workbench. */
  private edgeQueryParams(edge: Edge): Record<string, string> {
    return {
      ...this.carriedSearch(),
      tables: [tableKey(edge.from.schema, edge.from.table), tableKey(edge.to.schema, edge.to.table)].join(','),
      tableMode: 'ALL'
    };
  }

  private edgeQueries(edge: Edge) {
    return this.router.createUrlTree(['/queries'], { queryParams: this.edgeQueryParams(edge) });
  }

  private openEdge(edge: Edge, newTab: boolean) {
    const tree = this.edgeQueries(edge);
    if (newTab) {
      window.open(this.router.serializeUrl(tree), '_blank', 'noopener');
    } else {
      this.router.navigateByUrl(tree);
    }
  }

  private edgeTitle(edge: Edge): string {
    const kind = this.fkEdges.includes(edge)
        ? `foreign key ${edge.label}${edge.neverJoined ? ' · no query joins on it' : ''}`
      : this.joinEdges.includes(edge) ? `joined on ${edge.label} · ${this.fmtExecs(edge.execs || 0)} executions`
      : this.viewEdges.includes(edge) ? 'view built on it'
      : `queried together, never joined · ${this.fmtExecs(edge.execs || 0)} executions`;
    return `<div class="tt-name">${edge.from.table} ↔ ${edge.to.table}</div>`
      + `<div class="tt-line">${kind}</div>`
      + '<div class="tt-foot">click for the queries touching both, ⌘/ctrl-click for a new tab</div>';
  }

  @HostListener('document:mouseup')
  onMouseUp() {
    this.dragging = false;
    const drag = this.gridDrag;
    this.gridDrag = null;
    if (!drag?.active) { return; }
    const changes = this.dropChanges(drag.key, drag.x, drag.y);
    if (changes?.length) {
      this.arrange(changes);
    } else {
      this.draw();
    }
  }

  onMouseMove(event: MouseEvent) {
    if (this.gridDrag) {
      const rect = this.graphCanvas.nativeElement.getBoundingClientRect();
      this.gridDrag.x = this.worldX(event.clientX - rect.left);
      this.gridDrag.y = this.worldY(event.clientY - rect.top);
      this.gridDrag.active = this.gridDrag.active || this.wasDrag(event);
      this.tooltip.nativeElement.style.display = 'none';
      this.graphCanvas.nativeElement.style.cursor = 'grabbing';
      this.draw();
      return;
    }
    if (this.dragging) {
      this.tx += event.clientX - this.lastX;
      this.ty += event.clientY - this.lastY;
      this.lastX = event.clientX;
      this.lastY = event.clientY;
      this.draw();
      return;
    }
    const rect = this.graphCanvas.nativeElement.getBoundingClientRect();
    const mx = this.worldX(event.clientX - rect.left);
    const my = this.worldY(event.clientY - rect.top);
    const found = this.nodes.find(node => mx >= node.x && mx <= node.x + NW && my >= node.y && my <= node.y + NH) || null;
    const edgeUnder = found ? null : this.edgeAt(event);
    if (found !== this.hovered || edgeUnder !== this.hoveredEdge) {
      this.hovered = found;
      this.hoveredEdge = edgeUnder;
      this.draw();
    }
    const tooltip = this.tooltip.nativeElement;
    if (found) {
      tooltip.style.display = 'block';
      tooltip.style.left = (event.clientX - rect.left + 14) + 'px';
      tooltip.style.top = (event.clientY - rect.top - 8) + 'px';
      const fkOut = this.fkEdges.filter(edge => edge.from === found).map(edge => edge.to.table);
      const fkIn = this.fkEdges.filter(edge => edge.to === found).map(edge => edge.from.table);
      tooltip.innerHTML =
        `<div class="tt-name">${found.table}</div>`
        + (fkOut.length ? `<div class="tt-line">FK → ${fkOut.join(', ')}</div>` : '')
        + (fkIn.length ? `<div class="tt-line">FK ← ${fkIn.join(', ')}</div>` : '')
        + `<div class="tt-foot">${found.queryCount} queries · ${found.columnCount ?? '?'} columns · click to pin its details, again to open, ⌘/ctrl-click for a new tab</div>`;
    } else {
      const edge = edgeUnder;
      this.graphCanvas.nativeElement.style.cursor = edge ? 'pointer' : this.grabAt(mx, my) ? 'grab' : '';
      if (edge) {
        tooltip.style.display = 'block';
        tooltip.style.left = (event.clientX - rect.left + 14) + 'px';
        tooltip.style.top = (event.clientY - rect.top - 8) + 'px';
        tooltip.innerHTML = this.edgeTitle(edge);
      } else {
        tooltip.style.display = 'none';
      }
    }
  }

  /** The node under a pointer event, if any. */
  private nodeAt(event: MouseEvent): LayoutNode | undefined {
    const rect = this.graphCanvas.nativeElement.getBoundingClientRect();
    const mx = this.worldX(event.clientX - rect.left);
    const my = this.worldY(event.clientY - rect.top);
    return this.nodes.find(node => mx >= node.x && mx <= node.x + NW && my >= node.y && my <= node.y + NH);
  }

  /**
   * The canvas has no links for the browser to open, so the gestures a link
   * gets for free are handled here: cmd/ctrl-click and middle-click open the
   * table in a new tab (sqlx-aoy.5). The new tab carries the scope the
   * current page is under, as an in-app navigation would (carryScope), and
   * the environment: a fresh tab has no state to fall back on but the URL.
   */
  private openInNewTab(node: LayoutNode) {
    const params = this.route.snapshot.queryParams;
    const env = params[ENV_PARAM];
    const tree = this.router.createUrlTree(['/table', node.schema, node.table],
      { queryParams: {...scopeOf(params), ...(env ? {[ENV_PARAM]: env} : {})} });
    window.open(this.router.serializeUrl(tree), '_blank', 'noopener');
  }

  /** Middle-click: fires as auxclick, not click. */
  onAuxClick(event: MouseEvent) {
    if (event.button !== 1) { return; }
    const found = this.nodeAt(event);
    if (found) {
      event.preventDefault();
      this.openInNewTab(found);
      return;
    }
    const edge = this.edgeAt(event);
    if (edge) {
      event.preventDefault();
      this.openEdge(edge, true);
    }
  }

  onClick(event: MouseEvent) {
    if (this.wasDrag(event)) { return; }
    const found = this.nodeAt(event);
    if (!found) {
      const edge = this.edgeAt(event);
      if (edge) {
        this.openEdge(edge, event.metaKey || event.ctrlKey);
        return;
      }
    }
    if (found && (event.metaKey || event.ctrlKey)) {
      this.openInNewTab(found);
      return;
    }
    if (found && found.table === this.selectedTable) {
      // second click on the selected node opens it
      this.router.navigate(['/table', found.schema, found.table]);
      return;
    }
    const table = found ? found.table : null;
    if (table !== this.selectedTable) {
      this.selectedTable = table;
      this.draw();
      this.writeUrl();
    }
  }

  // ── the pinned detail of the selected node (sqlx-aoy.6) ──

  /** The selected node, if it is on the graph. */
  selectedNode(): LayoutNode | null {
    return this.selectedTable ? this.nodes.find(node => node.table === this.selectedTable) ?? null : null;
  }

  /**
   * What the hover says about the selected node, pinned and followable: its
   * foreign keys each way and the tables it is queried with, busiest first.
   * Each relationship carries its edge, so its queries are one click away.
   */
  selectedDetail(): {fkOut: Edge[], fkIn: Edge[], joins: {other: LayoutNode, edge: Edge}[],
                     co: {other: LayoutNode, edge: Edge}[]} | null {
    const node = this.selectedNode();
    if (!node) { return null; }
    const partners = (edges: Edge[]) => edges
      .filter(edge => edge.from === node || edge.to === node)
      .map(edge => ({other: edge.from === node ? edge.to : edge.from, edge}))
      .sort((a, b) => (b.edge.execs ?? 0) - (a.edge.execs ?? 0));
    return {
      fkOut: this.fkEdges.filter(edge => edge.from === node),
      fkIn: this.fkEdges.filter(edge => edge.to === node),
      joins: partners(this.joinEdges),
      co: partners(this.coEdges)
    };
  }

  /** Follow a relationship: select the table at its other end. */
  selectTable(table: string | null) {
    this.selectedTable = table;
    this.draw();
    this.writeUrl();
  }

  clearSelection() {
    this.selectedTable = null;
    this.draw();
    this.writeUrl();
  }

  edgeLink(edge: Edge): {path: string, query: Record<string, string>} {
    return {path: '/queries', query: this.edgeQueryParams(edge)};
  }

  /** The selected table's matching queries in the workbench. */
  tableQueriesLink(node: LayoutNode): Record<string, string> {
    return {...this.carriedSearch(), tables: tableKey(node.schema, node.table)};
  }

  readonly fmtExecsPublic = (value: number) => this.fmtExecs(value);

  onWheel(event: WheelEvent) {
    event.preventDefault();
    const rect = this.graphCanvas.nativeElement.getBoundingClientRect();
    const mx = event.clientX - rect.left;
    const my = event.clientY - rect.top;
    const factor = event.deltaY < 0 ? 1.12 : 0.89;
    this.tx = mx - (mx - this.tx) * factor;
    this.ty = my - (my - this.ty) * factor;
    this.scale *= factor;
    this.draw();
  }

  onMinimapClick(event: MouseEvent) {
    const minimap = this.minimapCanvas.nativeElement as MinimapCanvas;
    const scale = minimap._msc;
    if (!scale) { return; }
    const rect = this.minimapCanvas.nativeElement.getBoundingClientRect();
    const wx = (event.clientX - rect.left - (minimap._offX ?? 0)) / scale;
    const wy = (event.clientY - rect.top - (minimap._offY ?? 0)) / scale;
    const canvas = this.graphCanvas.nativeElement;
    this.tx = canvas.width / 2 - wx * this.scale;
    this.ty = canvas.height / 2 - wy * this.scale;
    this.draw();
  }

  // ── drawing ──

  private refreshPalette() {
    const style = getComputedStyle(document.documentElement);
    const read = (name: string, fallback: string) => style.getPropertyValue(name).trim() || fallback;
    this.palette = {
      blue: read('--blue', '#4a9eff'),
      amber: read('--amber', '#f5a623'),
      purple: read('--purple', '#b48cff'),
      text: read('--text', '#e8eaf0'),
      text2: read('--text2', '#8b93a8'),
      bg: read('--bg', '#0f1117'),
      bg2: read('--bg2', '#161b27'),
      bg3: read('--bg3', '#1e2535'),
      border2: read('--border2', 'rgba(255,255,255,0.14)'),
      green: read('--green', '#3dd68c'),
      teal: read('--teal', '#2dd4bf'),
      red: read('--red', '#f56565'),
    };
    const background = /^#([0-9a-f]{6})$/i.exec(this.palette.bg);
    if (background) {
      const n = parseInt(background[1], 16);
      this.darkTheme = (0.299 * ((n >> 16) & 255) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) < 128;
    }
    this.assignRegionColors();
  }

  /** #rrggbb → rgba(r,g,b,a); non-hex colors are returned unchanged. */
  private alpha(color: string, a: number): string {
    const match = /^#([0-9a-f]{6})$/i.exec(color);
    if (!match) { return color; }
    const n = parseInt(match[1], 16);
    return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
  }

  private draw() {
    if (!this.viewReady) { return; }
    const canvas = this.graphCanvas.nativeElement;
    const ctx = canvas.getContext('2d')!;
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // Regions first, so every edge and node draws over them. A cross-schema
    // join is the most valuable thing this graph shows and used to look
    // exactly like a local one; drawn this way it is visibly a line leaving
    // one area for another.
    this.drawRegions(ctx);
    this.drawAggregates(ctx);

    // On the grid, lines follow their routes and take their aggregate's
    // colour, so the kind of line is in its style; labels would bury the
    // routes, so only lines in focus carry them (sqlx-aoy.14).
    const routes = this.currentRoutes();
    const focus = this.focusEdges();
    const tint = (edge: Edge, layerColor: string, directed: boolean) =>
      routes ? this.aggregateColor(edge, directed, 0.85) : layerColor;
    const paint = (edge: Edge, color: string, dash: number[] | null, weight: number, head: boolean) => {
      const path = this.pathOf(edge, routes);
      this.drawPath(ctx, path, color, dash, weight, head, focus ? focus.has(edge) : null);
      return path;
    };
    const labelled = (edge: Edge) => !routes || !!focus?.has(edge);

    if (this.layers.co) {
      for (const edge of this.coEdges) {
        const path = paint(edge, tint(edge, this.alpha(this.palette.text2, 0.55), false), DASHED, 1, false);
        if (labelled(edge)) {
          this.drawLabel(ctx, this.fmtExecs(edge.execs || 0), this.labelPoint(path),
            this.alpha(this.palette.text2, 0.9), false, 0);
        }
      }
    }
    if (this.layers.join) {
      for (const edge of this.joinEdges) {
        const path = paint(edge, tint(edge, this.alpha(this.palette.green, 0.75), false), null, routes ? 2.2 : 1.5, false);
        if (labelled(edge)) {
          this.drawLabel(ctx, this.fmtExecs(edge.execs || 0), this.labelPoint(path),
            this.alpha(this.palette.green, 0.95), false, 10);
        }
      }
    }
    if (this.layers.view) {
      for (const edge of this.viewEdges) {
        paint(edge, tint(edge, this.alpha(this.palette.purple, 0.75), true), DASHED, 1.5, true);
      }
    }
    if (this.layers.fk) {
      for (const edge of this.fkEdges) {
        const color = edge.warn ? this.palette.amber : tint(edge, this.alpha(this.palette.blue, 0.7), true);
        const path = paint(edge, color, edge.neverJoined ? DOTTED : null, 1.5, true);
        if (labelled(edge)) {
          this.drawLabel(ctx, edge.label, this.labelPoint(path),
            edge.warn ? this.palette.amber : this.alpha(this.palette.blue, 0.9), true, 0);
        }
      }
    }
    if (this.layers.self) {
      for (const node of this.nodes.filter(n => n.selfJoin)) {
        const cx = this.wx(node.x + NW / 2);
        const cy = this.wy(node.y);
        ctx.save();
        ctx.strokeStyle = this.alpha(this.palette.text2, 0.5);
        ctx.lineWidth = 1.5 * this.scale;
        ctx.setLineDash([4 * this.scale, 3 * this.scale]);
        ctx.beginPath();
        ctx.arc(cx, cy - 14 * this.scale, 12 * this.scale, 0.3, Math.PI * 1.7);
        ctx.stroke();
        ctx.restore();
      }
    }
    for (const node of this.nodes) {
      this.drawNode(ctx, node);
    }
    this.drawHeld(ctx);
    this.drawMinimap();
  }

  private edgePorts(a: LayoutNode, b: LayoutNode): [number, number, number, number] {
    const ax = a.x + NW / 2;
    const ay = a.y + NH / 2;
    const bx = b.x + NW / 2;
    const by = b.y + NH / 2;
    const angle = Math.atan2(by - ay, bx - ax);
    const hw = NW / 2 + 4;
    const hh = NH / 2 + 4;
    const clip = (ox: number, oy: number): [number, number] => {
      const sx = Math.abs(ox) < 1e-9 ? 1e9 : hw / Math.abs(ox);
      const sy = Math.abs(oy) < 1e-9 ? 1e9 : hh / Math.abs(oy);
      const s = Math.min(sx, sy);
      return [ox * s, oy * s];
    };
    const [ex, ey] = clip(Math.cos(angle), Math.sin(angle));
    const [fx, fy] = clip(-Math.cos(angle), -Math.sin(angle));
    return [ax + ex, ay + ey, bx + fx, by + fy];
  }

  /**
   * A line along a path, with an arrowhead at its end if asked. `focused` is
   * null when nothing is in focus; otherwise a line in focus is drawn heavier
   * and every other line fades back, so a route can be followed end to end
   * through a crowded channel.
   */
  private drawPath(ctx: CanvasRenderingContext2D, path: Point[], color: string, dash: number[] | null,
                   weight: number, head: boolean, focused: boolean | null) {
    if (path.length < 2) { return; }
    ctx.save();
    ctx.strokeStyle = color;
    ctx.lineWidth = weight * (focused ? 1.7 : 1) * this.scale;
    ctx.lineJoin = 'round';
    ctx.globalAlpha = focused === null ? 0.8 : focused ? 1 : 0.12;
    if (dash) { ctx.setLineDash(dash.map(length => length * this.scale)); }
    ctx.beginPath();
    ctx.moveTo(this.wx(path[0].x), this.wy(path[0].y));
    for (const point of path.slice(1)) { ctx.lineTo(this.wx(point.x), this.wy(point.y)); }
    ctx.stroke();
    ctx.setLineDash([]);
    if (head) {
      const end = path[path.length - 1];
      const before = path[path.length - 2];
      const angle = Math.atan2(end.y - before.y, end.x - before.x);
      const size = 8 * this.scale;
      const x = this.wx(end.x);
      const y = this.wy(end.y);
      ctx.fillStyle = color;
      ctx.globalAlpha = focused === null ? 0.9 : focused ? 1 : 0.12;
      ctx.beginPath();
      ctx.moveTo(x, y);
      ctx.lineTo(x - size * Math.cos(angle - 0.4), y - size * Math.sin(angle - 0.4));
      ctx.lineTo(x - size * Math.cos(angle + 0.4), y - size * Math.sin(angle + 0.4));
      ctx.closePath();
      ctx.fill();
    }
    ctx.restore();
  }

  /** What is being moved, drawn as an outline following the pointer. */
  private drawHeld(ctx: CanvasRenderingContext2D) {
    const drag = this.gridDrag;
    if (!drag?.active) { return; }
    const box = drag.box;
    const x = this.wx(box.x + drag.x - drag.startX);
    const y = this.wy(box.y + drag.y - drag.startY);
    ctx.save();
    ctx.beginPath();
    ctx.roundRect(x, y, box.w * this.scale, box.h * this.scale, 6 * this.scale);
    ctx.fillStyle = this.alpha(this.palette.blue, 0.1);
    ctx.fill();
    ctx.strokeStyle = this.palette.blue;
    ctx.lineWidth = 1.5;
    ctx.setLineDash([5, 4]);
    ctx.stroke();
    ctx.restore();
  }

  /** Where a line's label goes: the middle of its longest stretch. */
  private labelPoint(path: Point[]): Point {
    let best = { x: (path[0].x + path[path.length - 1].x) / 2, y: (path[0].y + path[path.length - 1].y) / 2 };
    let longest = -1;
    for (let i = 1; i < path.length; i++) {
      const length = Math.hypot(path[i].x - path[i - 1].x, path[i].y - path[i - 1].y);
      if (length > longest) {
        longest = length;
        best = { x: (path[i].x + path[i - 1].x) / 2, y: (path[i].y + path[i - 1].y) / 2 };
      }
    }
    return best;
  }

  /** A line's label: plain, or mono on a backing box for foreign keys. `dy` is in world units. */
  private drawLabel(ctx: CanvasRenderingContext2D, text: string, at: Point, color: string, boxed: boolean, dy: number) {
    const x = this.wx(at.x);
    const y = this.wy(at.y) + dy * this.scale;
    ctx.save();
    ctx.font = `${Math.round(9 * this.scale)}px ${boxed ? 'monospace' : 'sans-serif'}`;
    if (boxed) {
      const width = ctx.measureText(text).width + 6;
      ctx.fillStyle = this.alpha(this.palette.bg, 0.85);
      ctx.fillRect(x - width / 2, y - 8 * this.scale, width, 16 * this.scale);
    }
    ctx.fillStyle = color;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(text, x, y);
    ctx.restore();
  }

  /**
   * One soft, labelled area per schema, behind the graph.
   *
   * <p>The fill is deliberately near-invisible. Node borders already carry
   * selection (blue) and mismatch (amber), and a region that competed with
   * them would trade a signal people rely on for one they do not yet. The
   * label is what actually names the area; the colour only has to make the
   * boundary findable.
   */
  private drawRegions(ctx: CanvasRenderingContext2D) {
    // drawn for a single schema too: the box's label is the only place a
    // table's schema is named, since the card no longer repeats it
    for (const region of this.regions) {
      const x = this.wx(region.x);
      const y = this.wy(region.y);
      const w = region.w * this.scale;
      const h = region.h * this.scale;
      ctx.save();
      ctx.beginPath();
      ctx.roundRect(x, y, w, h, 14 * this.scale);
      ctx.fillStyle = this.alpha(region.color, 0.07);
      ctx.fill();
      ctx.strokeStyle = this.alpha(region.color, 0.34);
      ctx.lineWidth = 1;
      ctx.setLineDash([6 * this.scale, 5 * this.scale]);
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.font = `600 ${Math.round(12 * this.scale)}px ${this.monoFont()}`;
      ctx.fillStyle = this.alpha(region.color, 0.85);
      ctx.textAlign = 'left';
      ctx.textBaseline = 'bottom';
      ctx.fillText(region.schema, x + 14 * this.scale, y - 5 * this.scale);
      ctx.restore();
    }
  }

  /** Each aggregate's block on the grid: an outline in its hue, which its lines share, and its root named. */
  private drawAggregates(ctx: CanvasRenderingContext2D) {
    for (const box of this.aggregateBoxes) {
      const x = this.wx(box.x);
      const y = this.wy(box.y);
      ctx.save();
      ctx.beginPath();
      ctx.roundRect(x, y, box.w * this.scale, box.h * this.scale, 8 * this.scale);
      ctx.fillStyle = this.hueColor(box.hue, 0.05);
      ctx.fill();
      ctx.strokeStyle = this.hueColor(box.hue, 0.5);
      ctx.lineWidth = 1;
      ctx.stroke();
      ctx.font = `${Math.round(10 * this.scale)}px ${this.monoFont()}`;
      ctx.fillStyle = this.hueColor(box.hue, 0.9);
      ctx.textAlign = 'left';
      ctx.textBaseline = 'middle';
      ctx.fillText(box.root, x + 10 * this.scale, y + 12 * this.scale);
      ctx.restore();
    }
  }

  /** Aggregate blocks, for asserting the grid layout's structure. */
  get aggregatesForTest(): readonly AggregateBox[] {
    return this.aggregateBoxes;
  }

  /**
   * The app's mono stack, so region labels match the rest of the UI. Read from
   * the same token the stylesheets use rather than restated, since a font
   * hard-coded here would silently diverge from every other label.
   */
  private monoFont(): string {
    return getComputedStyle(document.documentElement).getPropertyValue('--mono').trim()
      || 'monospace';
  }

  private drawNode(ctx: CanvasRenderingContext2D, node: LayoutNode) {
    const x = this.wx(node.x);
    const y = this.wy(node.y);
    const w = NW * this.scale;
    const h = NH * this.scale;
    const r = 5 * this.scale;
    const selected = node.table === this.selectedTable;
    const highlighted = node === this.hovered;
    ctx.save();
    ctx.beginPath();
    ctx.roundRect(x, y, w, h, r);
    ctx.fillStyle = selected ? this.alpha(this.palette.blue, 0.2)
      : highlighted ? this.alpha(this.palette.amber, 0.15)
      : this.alpha(this.palette.bg3, 0.95);
    ctx.fill();
    ctx.strokeStyle = selected ? this.palette.blue : node.warn ? this.palette.amber : this.palette.border2;
    ctx.lineWidth = (selected || node.warn ? 1.5 : 1) * this.scale;
    ctx.stroke();
    ctx.font = `500 ${Math.round(11 * this.scale)}px monospace`;
    ctx.fillStyle = selected ? this.palette.blue : node.warn ? this.palette.amber : this.palette.text;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(node.table, x + w / 2, y + h * 0.36, w - 8);
    ctx.font = `${Math.round(9 * this.scale)}px sans-serif`;
    ctx.fillStyle = this.alpha(this.palette.text2, 0.8);
    // the schema is named by the box around the card, not on every card
    ctx.fillText(`${node.columnCount ?? '?'}c · ${node.queryCount}q`, x + w / 2, y + h * 0.72, w - 8);
    if (node.view) {
      ctx.font = `600 ${Math.round(8 * this.scale)}px sans-serif`;
      ctx.fillStyle = this.palette.purple;
      ctx.textAlign = 'right';
      ctx.fillText('view', x + w - 5 * this.scale, y + 8 * this.scale);
    }
    ctx.restore();
  }

  private drawMinimap() {
    const minimap = this.minimapCanvas.nativeElement as MinimapCanvas;
    const ctx = minimap.getContext('2d')!;
    const mmW = minimap.width;
    const mmH = minimap.height;
    ctx.clearRect(0, 0, mmW, mmH);
    ctx.fillStyle = this.alpha(this.palette.bg2, 0.95);
    ctx.fillRect(0, 0, mmW, mmH);
    if (!this.nodes.length) { return; }
    const xs = this.nodes.map(node => node.x);
    const ys = this.nodes.map(node => node.y);
    const minX = Math.min(...xs) - 10;
    const maxX = Math.max(...xs) + NW + 10;
    const minY = Math.min(...ys) - 10;
    const maxY = Math.max(...ys) + NH + 10;
    const msc = Math.min(mmW / (maxX - minX), mmH / (maxY - minY)) * 0.9;
    const offX = (mmW - (maxX - minX) * msc) / 2 - minX * msc;
    const offY = (mmH - (maxY - minY) * msc) / 2 - minY * msc;
    if (this.layers.fk) {
      for (const edge of this.fkEdges) {
        ctx.strokeStyle = edge.warn ? this.alpha(this.palette.amber, 0.6) : this.alpha(this.palette.blue, 0.4);
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(edge.from.x * msc + offX + NW * msc / 2, edge.from.y * msc + offY + NH * msc / 2);
        ctx.lineTo(edge.to.x * msc + offX + NW * msc / 2, edge.to.y * msc + offY + NH * msc / 2);
        ctx.stroke();
      }
    }
    for (const node of this.nodes) {
      ctx.fillStyle = node.table === this.selectedTable ? this.alpha(this.palette.blue, 0.3)
        : node.warn ? this.alpha(this.palette.amber, 0.2)
        : this.alpha(this.palette.bg3, 0.8);
      ctx.strokeStyle = node.warn ? this.palette.amber : node.table === this.selectedTable ? this.palette.blue : this.palette.border2;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.roundRect(node.x * msc + offX, node.y * msc + offY, NW * msc, NH * msc, 2);
      ctx.fill();
      ctx.stroke();
    }
    const canvas = this.graphCanvas.nativeElement;
    ctx.strokeStyle = this.alpha(this.palette.blue, 0.6);
    ctx.lineWidth = 1.5;
    ctx.strokeRect((-this.tx / this.scale) * msc + offX, (-this.ty / this.scale) * msc + offY,
      (canvas.width / this.scale) * msc, (canvas.height / this.scale) * msc);
    minimap._msc = msc;
    minimap._offX = offX;
    minimap._offY = offY;
  }

  private fmtExecs(value: number): string {
    if (value >= 1_000_000) { return (value / 1_000_000).toFixed(1) + 'M'; }
    if (value >= 1_000) { return Math.round(value / 1_000) + 'K'; }
    return String(value);
  }
}
