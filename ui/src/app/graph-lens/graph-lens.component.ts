import {AfterViewInit, Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {FormsModule} from '@angular/forms';
import {forkJoin, map, of, Subscription, switchMap} from 'rxjs';
import {GraphV2, SchemaLensService} from '../schema-lens.service';
import {EnvironmentService} from '../environment.service';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ThemeService} from '../theme.service';
import {decodeGraphParams, DEFAULT_LAYERS, encodeGraphParams, GraphLayers, graphOwnedParamPatch, GraphSource} from './graph-url';
import {tableKey} from '../query-scope.service';
import {buildSearchRequest, decodeSearchParams, encodeSearchParams, resolveSchemaTagScope, UNCONSTRAINED_TAG_SCOPE} from '../search-url';

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
    bg: '#0f1117', bg2: '#161b27', bg3: '#1e2535', border2: 'rgba(255,255,255,0.14)' };

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
      }
    }
    if (this.scopeKey() === this.loadedScope) {
      this.draw();
    } else if (this.mode !== 'schema' || this.schema) {
      this.loadGraph();
    }
    // schema mode without a schema: loadSchemas() picks a default and loads
  }

  private scopeKey(): string {
    if (this.mode === 'search') {
      // the search params are the scope — normalize through the codec so
      // var- params and param order can't cause spurious refetches
      const search = encodeSearchParams(decodeSearchParams(this.route.snapshot.queryParamMap));
      return 'search|' + JSON.stringify(search);
    }
    return `${this.mode}|${this.tag ?? ''}|${this.mode === 'schema' ? this.schema : ''}`;
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
      if (!this.schema && this.schemaNames.length) {
        this.schema = this.schemaNames[0];
      }
      if (this.schema && this.mode === 'schema') {
        this.loadGraph();
      }
    });
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
    source.subscribe({
      next: graph => {
        this.buildLayout(graph);
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
  private loadSearchGraph() {
    const state = decodeSearchParams(this.route.snapshot.queryParamMap);
    const filter = state.schemaTagFilter;
    // exclusion reaches tagged tables as well as tagged schemas, so a shared
    // link has to resolve both halves — otherwise the same params mean one
    // thing on /queries and another here
    const tablesByTag$ = filter.excludeTags.length
      ? forkJoin(filter.excludeTags.map(tag => this.analyzedSqlService.getTablesByTag(tag))).pipe(
          map(perTag => new Map<string, string[]>(filter.excludeTags.map((tag, index) =>
            [tag, perTag[index].map(table => tableKey(table.schema, table.name))]))))
      : of(new Map<string, string[]>());
    const tagScope$ = filter.includeTags.length || filter.excludeTags.length
      ? this.schemaLensService.getSchemaTree().pipe(switchMap(tree => {
          const names = tree.schemas.map(schema => schema.name.toLowerCase());
          const paths = tree.schemas.map(schema => '/schema/' + schema.name);
          return forkJoin({
            byPath: this.analyzedSqlService.getTagsForPaths(paths),
            tablesByTag: tablesByTag$
          }).pipe(map(({ byPath, tablesByTag }) => {
            const tagsBySchema = new Map<string, string[]>(tree.schemas.map(schema =>
              [schema.name.toLowerCase(), (byPath['/schema/' + schema.name] ?? []).map(tag => tag.name)]));
            return resolveSchemaTagScope(names, tagsBySchema, filter, tablesByTag);
          }));
        }))
      : of(UNCONSTRAINED_TAG_SCOPE);
    this.loading = true;
    tagScope$.pipe(switchMap(tagScope =>
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

  private buildLayout(graph: GraphV2) {
    const byId = new Map<string, LayoutNode>();
    const radius = Math.max(200, graph.nodes.length * 26);
    this.nodes = graph.nodes.map((node, index) => {
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
        x: 400 + radius * Math.cos(angle),
        y: 300 + radius * Math.sin(angle)
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
  }

  private runForceLayout() {
    const springs = [...this.fkEdges, ...this.coEdges, ...this.viewEdges];
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
      // springs
      for (const edge of springs) {
        const dx = edge.to.x - edge.from.x;
        const dy = edge.to.y - edge.from.y;
        const dist = Math.max(1, Math.sqrt(dx * dx + dy * dy));
        const force = (dist - 230) * 0.02 * cooling;
        edge.from.x += dx / dist * force; edge.from.y += dy / dist * force;
        edge.to.x -= dx / dist * force; edge.to.y -= dy / dist * force;
      }
      // gravity toward center
      for (const node of this.nodes) {
        node.x += (400 - node.x) * 0.004 * cooling;
        node.y += (300 - node.y) * 0.004 * cooling;
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
    const minX = Math.min(...xs) - 40;
    const maxX = Math.max(...xs) + NW + 40;
    const minY = Math.min(...ys) - 40;
    const maxY = Math.max(...ys) + NH + 40;
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
    };
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
