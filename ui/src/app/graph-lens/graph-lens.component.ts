import {AfterViewInit, Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {FormsModule} from '@angular/forms';
import {forkJoin, map, of, Subscription, switchMap} from 'rxjs';
import {GraphV2, SchemaLensService} from '../schema-lens.service';
import {EnvironmentService} from '../environment.service';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ThemeService} from '../theme.service';
import {decodeGraphParams, DEFAULT_LAYERS, encodeGraphParams, GraphLayers, graphOwnedParamPatch, GraphSource} from './graph-url';
import {SchemaTagScope, tableKey} from '../query-scope.service';
import {buildSearchRequest, decodeSearchParams, encodeSearchParams, resolveSchemaTagScope, UNCONSTRAINED_TAG_SCOPE} from '../search-url';
import {scopeOf} from '../scope-url';
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

interface Edge { from: LayoutNode; to: LayoutNode; label: string; warn?: boolean; execs?: number; }

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

@Component({
  selector: 'app-graph-lens',
  templateUrl: './graph-lens.component.html',
  styleUrls: ['./graph-lens.component.scss'],
  imports: [FormsModule]
})
export class GraphLensComponent implements OnInit, AfterViewInit, OnDestroy {

  @ViewChild('graphCanvas') graphCanvas!: ElementRef<HTMLCanvasElement>;
  @ViewChild('minimapCanvas') minimapCanvas!: ElementRef<HTMLCanvasElement>;
  @ViewChild('wrap') wrap!: ElementRef<HTMLDivElement>;
  @ViewChild('tooltip') tooltip!: ElementRef<HTMLDivElement>;

  schemaNames: string[] = [];
  schema = '';
  selectedTable: string | null = null;
  layers: GraphLayers = { ...DEFAULT_LAYERS };
  loading = false;

  /** What scopes the graph: a schema, a tag's queries, or a workbench search snapshot. */
  mode: GraphSource = 'schema';
  tag: string | null = null;

  /** Scope of the last fetch — lets URL writes for selection/layers redraw without refetching. */
  private loadedScope: string | null = null;

  private nodes: LayoutNode[] = [];
  private fkEdges: Edge[] = [];
  private coEdges: Edge[] = [];
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
              private router: Router) { }

  ngOnInit() {
    this.refreshPalette();
    this.subscriptions.add(this.themeService.current$.subscribe(() => {
      this.refreshPalette();
      this.draw();
    }));
    this.subscriptions.add(this.route.paramMap.subscribe(() => this.applyRoute()));
    this.subscriptions.add(this.route.queryParamMap.subscribe(() => this.applyRoute()));
    this.subscriptions.add(this.environmentService.current$.subscribe(() => this.loadSchemas()));
  }

  /**
   * Route decides the source (?tag= → tag graph, ?source=search → workbench
   * snapshot, else :schema) and carries graph-owned state (var-table,
   * var-layers). Only a *scope* change refetches; selection/layer changes —
   * including our own replaceUrl writes and browser back/forward — redraw.
   */
  private applyRoute() {
    const state = decodeGraphParams(this.route.snapshot.queryParamMap);
    this.selectedTable = state.table;
    this.layers = state.layers;
    if (state.tag) {
      this.mode = 'tag';
      this.tag = state.tag;
    } else if (state.source === 'search') {
      this.mode = 'search';
      this.tag = null;
    } else {
      this.mode = 'schema';
      this.tag = null;
      const schema = this.route.snapshot.paramMap.get('schema');
      if (schema) {
        this.schema = schema;
      } else if (!this.schema && this.schemaNames.length) {
        // bare /graph, or leaving a scoped graph after the tree has loaded —
        // loadSchemas() only re-runs on an environment change, so the default
        // has to be applied here too
        this.schema = this.schemaNames[0];
      }
    }
    if (this.scopeKey() === this.loadedScope) {
      this.draw();
    } else if (this.mode !== 'schema' || this.schema) {
      this.loadGraph();
    }
    // schema mode without a schema: loadSchemas() picks a default and loads
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

  private scopeKey(): string {
    if (this.mode === 'search') {
      // the search params are the scope — normalize through the codec so
      // var- params and param order can't cause spurious refetches
      const search = encodeSearchParams(decodeSearchParams(this.route.snapshot.queryParamMap));
      return 'search|' + JSON.stringify(search);
    }
    // the travelling scope narrows what is drawn in every mode, so a change to
    // it is a scope change — leave it out and arriving at the same schema with
    // `system` newly excluded would redraw the old, unfiltered graph
    const scope = JSON.stringify(scopeOf(this.route.snapshot.queryParams));
    return `${this.mode}|${this.tag ?? ''}|${this.mode === 'schema' ? this.schema : ''}|${scope}`;
  }

  /**
   * Reflect graph-owned state into the URL without a history entry
   * (workbench pattern). A merge patch: the co-resident search params of
   * `source=search` links must survive selection/layer clicks.
   */
  private writeUrl() {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: graphOwnedParamPatch(this.selectedTable, this.layers),
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }

  ngAfterViewInit() {
    this.viewReady = true;
    this.resetView();
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
  }

  private loadSchemas() {
    this.schemaLensService.getSchemaTree().subscribe(tree => {
      this.schemaNames = tree.schemas.map(schema => schema.name);
      // Only schema mode has a schema. Defaulting it unconditionally is what
      // put a schema name into the toolbar of a tag or search graph that was
      // not showing that schema at all (sqlx-p7u).
      if (this.mode === 'schema' && !this.schema && this.schemaNames.length) {
        this.schema = this.schemaNames[0];
      }
      if (this.schema && this.mode === 'schema') {
        this.loadGraph();
      }
    });
  }

  /**
   * Leave a tag or search scope for a whole-schema graph.
   *
   * <p>The chip used to be a label with no way off it, so the only exit was
   * the schema select — which dropped the scope as a side effect of what read
   * like a narrowing control. Discarding a working set should be something you
   * did on purpose (sqlx-p7u).
   */
  clearScope() {
    this.selectedTable = null;
    this.router.navigate(['/graph'], {
      queryParams: encodeGraphParams({ table: null, layers: this.layers, tag: null, source: null })
    });
  }

  /**
   * What the search graph is scoped to, rather than how it was built.
   *
   * <p>`encodeSearchParams` omits defaults, so its keys are exactly the
   * filters that are doing something — no separate list to keep in step with
   * the search state.
   */
  searchSummary(): string {
    const active = this.activeSearchFilters();
    if (!active.length) { return 'all queries'; }
    return active.length <= 3
      ? active.join(', ')
      : `${active.slice(0, 3).join(', ')} +${active.length - 3}`;
  }

  searchScopeTitle(): string {
    const active = this.activeSearchFilters();
    const filters = active.length ? active.join(', ') : 'no filters — every query';
    return `graph scoped to a workbench search (${filters}). The URL carries the full search, so this link is shareable.`;
  }

  private activeSearchFilters(): string[] {
    const params = encodeSearchParams(decodeSearchParams(this.route.snapshot.queryParamMap));
    return Object.keys(params).filter(key => params[key] !== null && params[key] !== undefined);
  }

  onSchemaChange() {
    this.selectedTable = null;
    // layer toggles survive the schema switch; selection does not
    this.router.navigate(['/graph', this.schema], {
      queryParams: encodeGraphParams({ table: null, layers: this.layers, tag: null, source: null })
    });
  }

  private loadGraph() {
    this.loadedScope = this.scopeKey();
    if (this.mode === 'search') {
      this.loadSearchGraph();
      return;
    }
    const source = this.mode === 'tag' && this.tag
      ? this.schemaLensService.getTagGraph(this.tag)
      : this.schema ? this.schemaLensService.getSchemaGraph(this.schema) : null;
    if (!source) { return; }
    this.loading = true;
    // the search path filters server-side; these two do not, so the scope is
    // applied to what comes back. Without this, excluding `system` on /tables
    // and clicking through to a graph would bring the system tables straight
    // back — one parameter, two answers.
    forkJoin({ graph: source, scope: this.tagScope$() }).subscribe({
      next: ({ graph, scope }) => {
        this.buildLayout(this.scoped(graph, scope));
        this.loading = false;
        this.resetView();
      },
      error: () => this.loading = false
    });
  }

  /**
   * `source=search`: the URL carries the full workbench search (shared
   * vocabulary — see search-url.ts). The schema-tag filter arrives as
   * intent, so resolve it against the live tag map exactly like the tree
   * does, then post the assembled request.
   */
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

  /**
   * Drop the nodes the scope puts outside the working set.
   *
   * <p>Applied to the drawn graph rather than the request because the schema
   * and tag endpoints take no filter — and it needs no new endpoint, since
   * `buildLayout` already discards edges whose endpoints it cannot resolve.
   * Removing a node removes its edges for free.
   */
  private scoped(graph: GraphV2, scope: SchemaTagScope): GraphV2 {
    const excludedSchemas = new Set(scope.excludedSchemas.map(name => name.toLowerCase()));
    const excludedTables = new Set(scope.excludedTables);
    const included = scope.includedSchemas === null
      ? null : new Set(scope.includedSchemas.map(name => name.toLowerCase()));
    return {
      ...graph,
      nodes: graph.nodes.filter(node => {
        const schema = (node.schema ?? '').toLowerCase();
        if (excludedSchemas.has(schema)) { return false; }
        if (excludedTables.has(tableKey(node.schema, node.table))) { return false; }
        return included === null || included.has(schema);
      })
    };
  }

  private loadSearchGraph() {
    const state = decodeSearchParams(this.route.snapshot.queryParamMap);
    this.loading = true;
    this.tagScope$().pipe(switchMap(tagScope =>
      this.schemaLensService.getSearchGraph(buildSearchRequest(state, tagScope, { limit: null, offset: null }))
    )).subscribe({
      next: graph => {
        this.buildLayout(graph);
        this.loading = false;
        this.resetView();
      },
      error: () => this.loading = false
    });
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

  private buildLayout(graph: GraphV2) {
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
      .map(edge => ({ from: resolve(edge.fromSchema, edge.fromTable), to: resolve(edge.toSchema, edge.toTable), label: edge.label, warn: edge.mismatch }))
      .filter(edge => edge.from && edge.to) as Edge[];
    this.coEdges = graph.coQueryEdges
      .map(edge => ({ from: resolve(edge.aSchema, edge.aTable), to: resolve(edge.bSchema, edge.bTable), label: '', execs: edge.executions }))
      .filter(edge => edge.from && edge.to) as Edge[];
    this.viewEdges = (graph.viewEdges || [])
      .map(edge => ({ from: resolve(edge.fromSchema, edge.fromTable), to: resolve(edge.toSchema, edge.toTable), label: 'over' }))
      .filter(edge => edge.from && edge.to) as Edge[];
    this.runForceLayout();
    this.measureRegions();
    this.separateRegions();
  }

  private runForceLayout() {
    const springs = [...this.fkEdges, ...this.coEdges, ...this.viewEdges];
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
    this.dragging = true;
    this.lastX = event.clientX;
    this.lastY = event.clientY;
  }

  @HostListener('document:mouseup')
  onMouseUp() { this.dragging = false; }

  onMouseMove(event: MouseEvent) {
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
    if (found !== this.hovered) {
      this.hovered = found;
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
        + `<div class="tt-foot">${found.queryCount} queries · ${found.columnCount ?? '?'} columns · click to select, again to open</div>`;
    } else {
      tooltip.style.display = 'none';
    }
  }

  onClick(event: MouseEvent) {
    const rect = this.graphCanvas.nativeElement.getBoundingClientRect();
    const mx = this.worldX(event.clientX - rect.left);
    const my = this.worldY(event.clientY - rect.top);
    const found = this.nodes.find(node => mx >= node.x && mx <= node.x + NW && my >= node.y && my <= node.y + NH);
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

    if (this.layers.co) {
      for (const edge of this.coEdges) {
        this.drawArrow(ctx, edge, this.alpha(this.palette.text2, 0.55), true, 1, false);
        const [x1, y1, x2, y2] = this.edgePorts(edge.from, edge.to);
        ctx.save();
        ctx.font = `${Math.round(9 * this.scale)}px sans-serif`;
        ctx.fillStyle = this.alpha(this.palette.text2, 0.9);
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(this.fmtExecs(edge.execs || 0), this.wx((x1 + x2) / 2), this.wy((y1 + y2) / 2));
        ctx.restore();
      }
    }
    if (this.layers.view) {
      for (const edge of this.viewEdges) {
        this.drawArrow(ctx, edge, this.alpha(this.palette.purple, 0.75), true, 1.5, true);
      }
    }
    if (this.layers.fk) {
      for (const edge of this.fkEdges) {
        this.drawArrow(ctx, edge, edge.warn ? this.palette.amber : this.alpha(this.palette.blue, 0.7), false, 1.5, true);
        const [x1, y1, x2, y2] = this.edgePorts(edge.from, edge.to);
        const mx = (x1 + x2) / 2;
        const my = (y1 + y2) / 2;
        ctx.save();
        ctx.font = `${Math.round(9 * this.scale)}px monospace`;
        const width = ctx.measureText(edge.label).width + 6;
        ctx.fillStyle = this.alpha(this.palette.bg, 0.85);
        ctx.fillRect(this.wx(mx) - width / 2, this.wy(my) - 8 * this.scale, width, 16 * this.scale);
        ctx.fillStyle = edge.warn ? this.palette.amber : this.alpha(this.palette.blue, 0.9);
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(edge.label, this.wx(mx), this.wy(my));
        ctx.restore();
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

  private drawArrow(ctx: CanvasRenderingContext2D, edge: Edge, color: string, dashed: boolean, weight: number, head: boolean) {
    const [x1, y1, x2, y2] = this.edgePorts(edge.from, edge.to);
    ctx.save();
    ctx.strokeStyle = color;
    ctx.lineWidth = weight * this.scale;
    ctx.globalAlpha = 0.8;
    if (dashed) { ctx.setLineDash([5 * this.scale, 4 * this.scale]); }
    ctx.beginPath();
    ctx.moveTo(this.wx(x1), this.wy(y1));
    ctx.lineTo(this.wx(x2), this.wy(y2));
    ctx.stroke();
    ctx.setLineDash([]);
    if (head) {
      const angle = Math.atan2(y2 - y1, x2 - x1);
      const size = 8 * this.scale;
      ctx.fillStyle = color;
      ctx.globalAlpha = 0.9;
      ctx.beginPath();
      ctx.moveTo(this.wx(x2), this.wy(y2));
      ctx.lineTo(this.wx(x2) - size * Math.cos(angle - 0.4), this.wy(y2) - size * Math.sin(angle - 0.4));
      ctx.lineTo(this.wx(x2) - size * Math.cos(angle + 0.4), this.wy(y2) - size * Math.sin(angle + 0.4));
      ctx.closePath();
      ctx.fill();
    }
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
    if (this.regions.length < 2) { return; }   // one schema needs no boundary
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
    ctx.fillText(`${node.schema} · ${node.columnCount ?? '?'}c · ${node.queryCount}q`, x + w / 2, y + h * 0.72, w - 8);
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
