import {ComponentFixture, TestBed, waitForAsync} from '@angular/core/testing';
import {ActivatedRoute, convertToParamMap, ParamMap, Router} from '@angular/router';
import {BehaviorSubject} from 'rxjs';
import {HttpTestingController} from '@angular/common/http/testing';
import {COMMON_TEST_PROVIDERS} from '../testing/common-test-providers';
import {GraphLensComponent} from './graph-lens.component';

/**
 * What is tested here is the toolbar's honesty about scope, which is where
 * sqlx-p7u lived: a tag or search graph used to render a schema select
 * defaulted to a schema it was not showing, and changing it discarded the
 * scope as a side effect of what read like a narrowing control.
 *
 * The graph drawing itself is canvas work and is not exercised.
 */
describe('GraphLensComponent — scope legibility', () => {

  let fixture: ComponentFixture<GraphLensComponent>;
  let component: GraphLensComponent;
  let router: Router;
  let params: BehaviorSubject<ParamMap>;
  let queryParams: BehaviorSubject<ParamMap>;

  /** Route stub whose snapshot tracks the subjects, the way a real one does. */
  function routeStub() {
    return {
      get snapshot() {
        return {
          paramMap: params.value,
          queryParamMap: queryParams.value,
          // scopeKey() reads the raw params, not just the map
          queryParams: Object.fromEntries(
            queryParams.value.keys.map(key => [key, queryParams.value.get(key)]))
        };
      },
      paramMap: params,
      queryParamMap: queryParams
    };
  }

  function navigateTo(routeParams: Record<string, string>, query: Record<string, string>) {
    params.next(convertToParamMap(routeParams));
    queryParams.next(convertToParamMap(query));
    fixture.detectChanges();
  }

  function toolbar(): HTMLElement {
    return (fixture.nativeElement as HTMLElement).querySelector('.graph-toolbar') as HTMLElement;
  }

  beforeEach(waitForAsync(() => {
    params = new BehaviorSubject<ParamMap>(convertToParamMap({}));
    queryParams = new BehaviorSubject<ParamMap>(convertToParamMap({}));
    TestBed.configureTestingModule({
      imports: [GraphLensComponent],
      providers: [COMMON_TEST_PROVIDERS, {provide: ActivatedRoute, useValue: routeStub()}]
    }).compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(GraphLensComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.returnValue(Promise.resolve(true));
    fixture.detectChanges();
  });

  /**
   * Resolve the schema-tree request that the environment subscription fires.
   * Deliberately per-test and *after* navigating: flushing it up front would
   * default the schema while the mode is still 'schema', which is exactly the
   * ordering the fix depends on.
   */
  function loadSchemaTree(): void {
    // `schema-tree` exactly — `graph/v2/schema/...` also contains "schema",
    // and answering that with a tree body reaches buildLayout
    TestBed.inject(HttpTestingController).match(req => req.url.endsWith('schema-tree'))
      .forEach(req => req.flush({schemas: [{name: 'app'}, {name: 'archive'}]}));
    fixture.detectChanges();
  }

  /** Whatever graph the current scope asked for. Empty is enough here. */
  function answerGraph(): void {
    TestBed.inject(HttpTestingController).match(req => req.url.includes('graph/v2'))
      .forEach(req => req.flush({nodes: [], fkEdges: [], coQueryEdges: [], viewEdges: []}));
    fixture.detectChanges();
  }

  // ── the select must not describe a graph it is not showing ──

  // ── a node opens in a new tab the way a link would (sqlx-aoy.5) ──

  describe('opening a node in a new tab', () => {
    const node = {id: 'app.orders', schema: 'app', table: 'orders', columnCount: 3, queryCount: 2,
      warn: false, selfJoin: false, view: false, x: 0, y: 0};
    let open: jasmine.Spy;

    beforeEach(() => {
      navigateTo({}, {schemas: 'app', env: 'staging'});
      // the hit test is canvas geometry; what is under test is what a hit does
      spyOn(component as unknown as {nodeAt: () => unknown}, 'nodeAt').and.returnValue(node);
      open = spyOn(window, 'open');
    });

    it('cmd-click and ctrl-click open the table lens in a new tab, with scope and environment', () => {
      component.onClick(new MouseEvent('click', {metaKey: true}));
      component.onClick(new MouseEvent('click', {ctrlKey: true}));

      expect(open).toHaveBeenCalledTimes(2);
      const [url, target] = open.calls.mostRecent().args;
      expect(target).toBe('_blank');
      expect(url).toBe('/table/app/orders?schemas=app&env=staging');
      expect(router.navigate).not.toHaveBeenCalledWith(['/table', 'app', 'orders']);
    });

    it('middle-click opens a new tab too', () => {
      component.onAuxClick(new MouseEvent('auxclick', {button: 1}));
      expect(open).toHaveBeenCalledTimes(1);
    });

    it('a plain click still selects rather than opening anything', () => {
      component.onClick(new MouseEvent('click'));
      expect(open).not.toHaveBeenCalled();
    });
  });

  // an edge opens the queries behind it (sqlx-aoy.4)
  describe('clicking an edge', () => {
    const node = (table: string, x: number) => ({id: 'app.' + table, schema: 'app', table, columnCount: 3,
      queryCount: 2, warn: false, selfJoin: false, view: false, x, y: 0});
    let at: (x: number, y: number, init?: MouseEventInit) => MouseEvent;

    beforeEach(() => {
      const a = node('account', 0);
      const b = node('site', 400);
      const internals = component as unknown as {nodes: unknown[]; coEdges: unknown[]; fkEdges: unknown[];
        viewEdges: unknown[]; scale: number; tx: number; ty: number};
      internals.nodes = [a, b];
      internals.coEdges = [{from: a, to: b, label: '', execs: 1200}];
      internals.fkEdges = [];
      internals.viewEdges = [];
      // co-query edges are a layer that starts hidden, and hidden edges are not clickable
      component.layers = {...component.layers, co: true};
      internals.scale = 1;
      internals.tx = 0;
      internals.ty = 0;
      const rect = (fixture.nativeElement as HTMLElement).querySelector('canvas')!.getBoundingClientRect();
      at = (x, y, init = {}) => new MouseEvent('click', {clientX: rect.left + x, clientY: rect.top + y, ...init});
      (router.navigate as jasmine.Spy).calls.reset();
    });

    it('opens the workbench on the queries touching both tables', () => {
      const navigate = spyOn(router, 'navigateByUrl').and.returnValue(Promise.resolve(true));
      component.onMouseDown(at(265, 22));
      component.onClick(at(265, 22));

      expect(navigate).toHaveBeenCalled();
      expect(router.serializeUrl(navigate.calls.mostRecent().args[0] as never))
        .toBe('/queries?tables=app.account,app.site&tableMode=ALL');
    });

    it('ignores a click well off the line, and one that ends a pan', () => {
      const navigate = spyOn(router, 'navigateByUrl').and.returnValue(Promise.resolve(true));
      component.onMouseDown(at(265, 120));
      component.onClick(at(265, 120));
      component.onMouseDown(at(100, 22));
      component.onClick(at(265, 22));

      expect(navigate).not.toHaveBeenCalled();
    });
  });

  // the full key opens from the legend and is kept in a link (sqlx-aoy.2)
  it('opens the full legend from var-legend, and explains the node marks', () => {
    expect(component.legendOpen).toBe(false);
    navigateTo({}, {'var-legend': '1'});
    expect(component.legendOpen).toBe(true);
    const text = (fixture.nativeElement as HTMLElement).querySelector('.graph-legend')?.textContent ?? '';
    expect(text).toContain('size means nothing');
    expect(text).toContain('executions of the queries behind it');
  });

  // a selected node's detail is pinned and followable (sqlx-aoy.6)
  it('pins the selected node\'s relationships, busiest partner first, and follows one', () => {
    const node = (table: string) => ({id: 'app.' + table, schema: 'app', table, columnCount: 3,
      queryCount: 2, warn: false, selfJoin: false, view: false, x: 0, y: 0});
    const [account, site, note] = [node('account'), node('site'), node('note')];
    const internals = component as unknown as {nodes: unknown[]; coEdges: unknown[]; fkEdges: unknown[]};
    internals.nodes = [account, site, note];
    internals.fkEdges = [{from: account, to: site, label: 'site_id → id'}];
    internals.coEdges = [{from: note, to: account, label: '', execs: 10}, {from: account, to: site, label: '', execs: 900}];
    component.selectedTable = 'account';

    const detail = component.selectedDetail()!;
    expect(detail.fkOut.map(edge => edge.to.table)).toEqual(['site']);
    expect(detail.co.map(partner => partner.other.table)).toEqual(['site', 'note']);
    const link = component.edgeLink(detail.co[0].edge);
    expect(link.query['tables']).toBe('app.account,app.site');
    expect(link.query['tableMode']).toBe('ALL');

    component.selectTable('note');
    expect(component.selectedNode()?.table).toBe('note');
  });

  // joins, queried-together and unjoined foreign keys are told apart (sqlx-aoy.3)
  it('draws a joined pair once, as a join, and marks a foreign key nothing joins on', () => {
    const graph = {
      nodes: ['account', 'site', 'note'].map(table =>
        ({schema: 'app', table, columnCount: 2, queryCount: 1, warn: false, selfJoin: false, view: false})),
      fkEdges: [
        {fromSchema: 'app', fromTable: 'site', toSchema: 'app', toTable: 'account', label: 'account_id→id', mismatch: false, joined: true},
        {fromSchema: 'app', fromTable: 'note', toSchema: 'app', toTable: 'account', label: 'account_id→id', mismatch: false, joined: false}
      ],
      coQueryEdges: [
        {aSchema: 'app', aTable: 'account', bSchema: 'app', bTable: 'site', queries: 1, executions: 50},
        {aSchema: 'app', aTable: 'account', bSchema: 'app', bTable: 'note', queries: 1, executions: 7}
      ],
      viewEdges: [],
      joinEdges: [{aSchema: 'app', aTable: 'account', bSchema: 'app', bTable: 'site',
        columns: ['id=account_id'], queries: 1, executions: 50}]
    };
    type Built = {from: {table: string}; to: {table: string}; label: string; neverJoined?: boolean};
    const internals = component as unknown as {buildLayout(graph: unknown): void;
      joinEdges: Built[]; coEdges: Built[]; fkEdges: Built[]};
    internals.buildLayout(graph);

    expect(internals.joinEdges.map(edge => [edge.from.table, edge.to.table, edge.label]))
      .toEqual([['account', 'site', 'id=account_id']]);
    // account–site is joined, so only account–note is left as "queried together"
    expect(internals.coEdges.map(edge => edge.to.table)).toEqual(['note']);
    expect(internals.fkEdges.map(edge => [edge.from.table, edge.neverJoined]))
      .toEqual([['site', false], ['note', true]]);

    component.selectedTable = 'account';
    const detail = component.selectedDetail()!;
    expect(detail.joins.map(partner => partner.other.table)).toEqual(['site']);
    expect(detail.co.map(partner => partner.other.table)).toEqual(['note']);
  });

  // the grid layout: schemas, then aggregates, then tables (sqlx-aoy.13)
  it('lays a graph out on grids, with an aggregate block and no overlapping tables', () => {
    const node = (schema: string, table: string) =>
      ({schema, table, columnCount: 2, queryCount: 1, warn: false, selfJoin: false, view: false});
    const graph = {
      nodes: [node('app_main', 'invoice'), node('app_main', 'invoice_item'), node('app_main', 'account'),
        node('app_main', 'account_note'), node('app_other', 'site')],
      fkEdges: [{fromSchema: 'app_main', fromTable: 'invoice_item', toSchema: 'app_main', toTable: 'invoice',
        label: 'invoice_id→id', mismatch: false, joined: true}],
      coQueryEdges: [],
      viewEdges: [],
      // account_note matches account by name but nothing relates them: no aggregate
      joinEdges: []
    };
    const internals = component as unknown as {buildLayout(graph: unknown): void;
      nodes: {table: string; x: number; y: number}[]};
    component.layout = 'grid';
    internals.buildLayout(graph);

    expect(component.aggregatesForTest.map(box => box.root)).toEqual(['invoice']);
    const block = component.aggregatesForTest[0];
    const invoice = internals.nodes.find(n => n.table === 'invoice')!;
    const item = internals.nodes.find(n => n.table === 'invoice_item')!;
    for (const member of [invoice, item]) {
      expect(member.x >= block.x && member.y >= block.y && member.x < block.x + block.w && member.y < block.y + block.h)
        .toBeTrue();
    }
    const boxes = internals.nodes;
    for (let i = 0; i < boxes.length; i++) {
      for (let j = i + 1; j < boxes.length; j++) {
        const apart = Math.abs(boxes[i].x - boxes[j].x) >= 130 || Math.abs(boxes[i].y - boxes[j].y) >= 44;
        expect(apart).withContext(`${boxes[i].table} and ${boxes[j].table}`).toBeTrue();
      }
    }
    // each schema's region holds its tables, and the two regions do not overlap
    const [main, other] = component.regionsForTest;
    expect(main.x + main.w <= other.x || other.x + other.w <= main.x
      || main.y + main.h <= other.y || other.y + other.h <= main.y).toBeTrue();
  });

  // routes through the gutters (sqlx-aoy.14)
  it('routes lines on the grid around tables, re-routing when a layer is toggled, and keeps them straight otherwise', () => {
    const node = (schema: string, table: string) =>
      ({schema, table, columnCount: 2, queryCount: 1, warn: false, selfJoin: false, view: false});
    const graph = {
      nodes: ['account', 'invoice', 'invoice_item', 'note', 'site'].map(table => node('app_main', table)),
      fkEdges: [{fromSchema: 'app_main', fromTable: 'invoice_item', toSchema: 'app_main', toTable: 'invoice',
        label: 'invoice_id→id', mismatch: false, joined: true}],
      coQueryEdges: [{aSchema: 'app_main', aTable: 'account', bSchema: 'app_main', bTable: 'site', queries: 1, executions: 5}],
      viewEdges: [],
      joinEdges: [{aSchema: 'app_main', aTable: 'invoice_item', bSchema: 'app_main', bTable: 'note',
        columns: ['note_id=id'], queries: 2, executions: 40}]
    };
    const internals = component as unknown as {buildLayout(graph: unknown): void;
      nodes: {table: string; x: number; y: number}[]};
    component.layout = 'force';
    internals.buildLayout(graph);
    expect(component.routesForTest()).toBeNull();

    component.layout = 'grid';
    component.toggle('fk');
    internals.buildLayout(graph);
    const routes = component.routesForTest()!;
    // join is on by default; fk was switched on above, co is still off
    expect(routes.size).toBe(2);
    for (const [edge, path] of routes) {
      for (let i = 1; i < path.length; i++) {
        expect(path[i].x === path[i - 1].x || path[i].y === path[i - 1].y).toBeTrue();
      }
      for (const other of internals.nodes.filter(n => n !== edge.from && n !== edge.to)) {
        for (let i = 1; i < path.length; i++) {
          const left = Math.min(path[i].x, path[i - 1].x), right = Math.max(path[i].x, path[i - 1].x);
          const top = Math.min(path[i].y, path[i - 1].y), bottom = Math.max(path[i].y, path[i - 1].y);
          expect(left < other.x + 130 - 0.5 && right > other.x + 0.5 && top < other.y + 44 - 0.5 && bottom > other.y + 0.5)
            .withContext(`${edge.from.table}→${edge.to.table} through ${other.table}`).toBeFalse();
        }
      }
    }

    component.toggle('co');
    expect(component.routesForTest()!.size).toBe(3);
  });

  // arranging the grid by hand (sqlx-aoy.15)
  describe('arranging the grid', () => {
    const node = (table: string) =>
      ({schema: 'app_main', table, columnCount: 2, queryCount: 1, warn: false, selfJoin: false, view: false});
    const graph = {
      nodes: ['account', 'invoice', 'invoice_item', 'site'].map(node),
      fkEdges: [{fromSchema: 'app_main', fromTable: 'invoice_item', toSchema: 'app_main', toTable: 'invoice',
        label: 'invoice_id→id', mismatch: false, joined: true}],
      coQueryEdges: [], viewEdges: [], joinEdges: []
    };
    interface Internals {
      buildLayout(graph: unknown): void;
      dropChanges(key: string, x: number, y: number):
        {kind: string; schema: string; name: string; slot?: unknown; parent?: string | null}[] | null;
      nodes: {table: string; x: number; y: number}[];
    }
    let internals: Internals;
    const http = () => TestBed.inject(HttpTestingController);
    const at = (table: string) => internals.nodes.find(n => n.table === table)!;

    beforeEach(() => {
      internals = component as unknown as Internals;
      component.layout = 'grid';
      internals.buildLayout(graph);
    });

    it('lays out from the saved arrangement once it loads: slots and aggregate overrides', () => {
      http().match(req => req.url.endsWith('graph/layout')).forEach(req => req.flush([
        {kind: 'table', schema: 'app_main', name: 'account', col: null, row: null, override: true, parent: 'site'},
        {kind: 'aggregate', schema: 'app_main', name: 'invoice', col: 5, row: 0, override: false, parent: null}
      ]));
      expect(component.aggregatesForTest.map(box => box.root).sort()).toEqual(['invoice', 'site']);
      const invoice = component.gridItemsForTest().find(item => item.key === 'a:app_main.invoice')!;
      expect(invoice.slot).toEqual({col: 5, row: 0});
    });

    it('joins a table to the aggregate it is dropped in', () => {
      const changes = internals.dropChanges('t:app_main.account', at('invoice_item').x + 5, at('invoice_item').y + 5)!;
      expect(changes[0]).toEqual({kind: 'table', schema: 'app_main', name: 'account', parent: 'invoice'});
      expect(changes.filter(change => change.slot).map(change => change.name).sort())
        .toEqual(['account', 'invoice', 'invoice_item']);
    });

    it('makes a new aggregate when a table is dropped on one that is in none', () => {
      const changes = internals.dropChanges('t:app_main.account', at('site').x + 5, at('site').y + 5)!;
      expect(changes.find(change => change.name === 'account')!.parent).toBe('site');
      expect(changes.find(change => change.kind === 'aggregate')!.name).toBe('site');
    });

    it('takes a member out of its aggregate when dropped on the open schema', () => {
      const site = at('site');
      const changes = internals.dropChanges('t:app_main.invoice_item', site.x + 400, site.y + 5)!;
      expect(changes[0]).toEqual({kind: 'table', schema: 'app_main', name: 'invoice_item', parent: null});
    });

    it('moves nothing across schemas', () => {
      expect(internals.dropChanges('t:app_main.account', -5000, -5000)).toBeNull();
    });

    it('applies a move at once and saves the merged items in one batch', () => {
      http().match(req => req.url.endsWith('graph/layout')).forEach(req => req.flush([]));
      const site = at('site');
      (component as unknown as {arrange(changes: unknown[]): void}).arrange(
        internals.dropChanges('t:app_main.account', site.x + 5, site.y + 5)!);
      expect(component.aggregatesForTest.map(box => box.root).sort()).toEqual(['invoice', 'site']);
      const put = http().expectOne(req => req.method === 'PUT' && req.url.endsWith('graph/layout'));
      expect((put.request.body as {name: string; override: boolean}[])
        .find(item => item.name === 'account')!.override).toBeTrue();
      put.flush([]);
    });

    it('says so, and reloads what is saved, when a move cannot be saved', () => {
      http().match(req => req.url.endsWith('graph/layout')).forEach(req => req.flush([]));
      const site = at('site');
      (component as unknown as {arrange(changes: unknown[]): void}).arrange(
        internals.dropChanges('t:app_main.account', site.x + 5, site.y + 5)!);
      http().expectOne(req => req.method === 'PUT').flush({message: 'nope'}, {status: 400, statusText: 'Bad Request'});
      expect(component.layoutError).toContain('not saved');
      http().expectOne(req => req.method === 'GET' && req.url.endsWith('graph/layout')).flush([]);
      expect(component.aggregatesForTest.map(box => box.root)).toEqual(['invoice']);
    });
  });

  it('switches layout without refetching, and writes var-layout', () => {
    const internals = component as unknown as {buildLayout(graph: unknown): void};
    internals.buildLayout({nodes: [{schema: 'app_main', table: 'account', columnCount: 1, queryCount: 1,
      warn: false, selfJoin: false, view: false}], fkEdges: [], coQueryEdges: [], viewEdges: []});
    const navigate = router.navigate as jasmine.Spy;
    navigate.calls.reset();

    // the grid is the default, so an untouched page says nothing about layout
    expect(component.layout).toBe('grid');
    component.toggleLayout();
    expect(component.layout).toBe('force');
    expect(navigate.calls.mostRecent().args[1].queryParams['var-layout']).toBe('force');
    component.toggleLayout();
    expect(navigate.calls.mostRecent().args[1].queryParams['var-layout']).toBeNull();
  });

  // scoping by tag from the toolbar (sqlx-aoy.7)
  it('scopes to a query tag as a tag graph, and a schema tag as a search graph', () => {
    component.tagChoices = [
      {name: 'billing', kind: 'queries', label: '4 queries', why: ''},
      {name: 'core', kind: 'schemas', label: '2 schemas', why: ''},
      {name: 'pii', kind: null, label: '3 tables', why: ''}
    ];
    const navigate = router.navigate as jasmine.Spy;
    navigate.calls.reset();

    component.scopeToTag('billing');
    expect(navigate.calls.mostRecent().args[1].queryParams['tag']).toBe('billing');

    component.scopeToTag('core');
    const search = navigate.calls.mostRecent().args[1].queryParams;
    expect(search['source']).toBe('search');
    expect(search['schemaTags']).toBe('core');

    navigate.calls.reset();
    component.scopeToTag('pii');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('offers the schema select on a schema graph', () => {
    navigateTo({schema: 'app'}, {});
    loadSchemaTree();
    answerGraph();

    expect(toolbar().querySelector('.schema-select')).toBeTruthy();
  });

  it('hides the schema select on a tag graph', () => {
    navigateTo({}, {tag: 'system'});
    loadSchemaTree();
    answerGraph();

    expect(component.mode).toBe('tag');
    expect(toolbar().querySelector('.schema-select')).toBeNull();
  });

  it('hides the schema select on a search graph', () => {
    navigateTo({}, {source: 'search'});
    loadSchemaTree();
    answerGraph();

    expect(component.mode).toBe('search');
    expect(toolbar().querySelector('.schema-select')).toBeNull();
  });

  /**
   * The root of the bug: loadSchemas() defaulted `schema` regardless of mode,
   * so a tag graph acquired the first schema alphabetically — a name nobody
   * chose, describing nothing on screen.
   */
  it('does not adopt a default schema behind a scoped graph', () => {
    navigateTo({}, {tag: 'system'});
    loadSchemaTree();
    answerGraph();

    expect(component.schema).toBe('');
  });

  /**
   * A schema you *did* choose is remembered, so leaving the scope returns you
   * to it. That is fine precisely because the select is hidden meanwhile —
   * the defect was displaying it, not holding it.
   */
  it('remembers a chosen schema while scoped, without showing it', () => {
    navigateTo({schema: 'archive'}, {});
    loadSchemaTree();
    answerGraph();

    navigateTo({}, {tag: 'system'});

    expect(component.schema).toBe('archive');
    expect(toolbar().querySelector('.schema-select')).toBeNull();
  });



  // ── per-schema regions (sqlx-70t) ──

  describe('schema regions', () => {

    /** `{app: 3}` → three tables in `app`. Counts matter: regions grow with them. */
    function graphAcross(sizes: Record<string, number>, joinAcross = false) {
      const nodes = Object.entries(sizes).flatMap(([schema, count]) =>
        Array.from({length: count}, (_, index) => ({
          schema, table: `t${index}`, columnCount: 2, queryCount: 1,
          warn: false, selfJoin: false, view: false
        })));
      // a cross-schema join is the case that pulls regions together, so it is
      // the one worth asserting they survive
      const names = Object.keys(sizes);
      const fkEdges = joinAcross && names.length > 1
        ? names.slice(1).map(schema => ({
            fromSchema: names[0], fromTable: 't0', toSchema: schema, toTable: 't0',
            label: 'fk', mismatch: false
          }))
        : [];
      TestBed.inject(HttpTestingController).match(req => req.url.includes('graph/v2'))
        .forEach(req => req.flush({nodes, fkEdges, coQueryEdges: [], viewEdges: []}));
      fixture.detectChanges();
    }

    it('makes one region per schema', () => {
      navigateTo({schema: 'app'}, {});
      loadSchemaTree();
      graphAcross({app: 2, del: 2, search: 2});

      expect(component.regionSchemas).toEqual(['app', 'del', 'search']);
    });

    /**
     * Sorted, not arrival-ordered. A colour that moves between loads carries
     * no information, and arrival order changes whenever the query does.
     */
    it('orders schemas so a colour is stable across loads', () => {
      navigateTo({schema: 'app'}, {});
      loadSchemaTree();
      graphAcross({search: 1, app: 1, del: 1});

      expect(component.regionSchemas).toEqual(['app', 'del', 'search']);
    });

    it('gives every region a box that contains its own nodes', () => {
      navigateTo({schema: 'app'}, {});
      loadSchemaTree();
      graphAcross({app: 4, del: 3});

      for (const region of component.regionsForTest) {
        expect(region.w).toBeGreaterThan(0);
        expect(region.h).toBeGreaterThan(0);
      }
    });

    /**
     * The load-bearing one. Regions that overlap make the colours meaningless,
     * and overlap is what the layout has to earn: several tables per schema
     * grow the boxes, and a cross-schema join actively pulls them together.
     */
    it('keeps regions apart, even densely populated and joined across', () => {
      navigateTo({schema: 'app'}, {});
      loadSchemaTree();
      graphAcross({app: 6, del: 5, search: 4, app_main: 3}, true);

      const boxes = component.regionsForTest;
      for (let i = 0; i < boxes.length; i++) {
        for (let j = i + 1; j < boxes.length; j++) {
          const a = boxes[i], b = boxes[j];
          const apart = a.x + a.w <= b.x || b.x + b.w <= a.x
            || a.y + a.h <= b.y || b.y + b.h <= a.y;
          expect(apart).withContext(`${a.schema} overlaps ${b.schema}`).toBeTrue();
        }
      }
    });


    /** Well past anything the corpus produces, to find the breaking point. */
    it('holds with many schemas of very different sizes', () => {
      navigateTo({schema: 'app'}, {});
      loadSchemaTree();
      graphAcross({a: 24, b: 2, c: 11, d: 3, e: 18, f: 1, g: 7, h: 9}, true);

      const boxes = component.regionsForTest;
      expect(boxes.length).toBe(8);
      for (let i = 0; i < boxes.length; i++) {
        for (let j = i + 1; j < boxes.length; j++) {
          const a = boxes[i], b = boxes[j];
          const apart = a.x + a.w <= b.x || b.x + b.w <= a.x
            || a.y + a.h <= b.y || b.y + b.h <= a.y;
          expect(apart).withContext(`${a.schema} overlaps ${b.schema}`).toBeTrue();
        }
      }
    });

    it('takes its colours from the theme rather than hard-coding them', () => {
      navigateTo({schema: 'app'}, {});
      loadSchemaTree();
      graphAcross({app: 2, del: 2});

      const colors = component.regionsForTest.map(region => region.color);
      expect(new Set(colors).size).toBe(2);
      colors.forEach(color => expect(color).toBeTruthy());
    });
  });

  // ── the travelling scope applies here too (sqlx-uxo) ──

  describe('the working scope', () => {

    /** One graph payload spanning two schemas, one of which is tagged `system`. */
    function answerScopedGraph() {
      const http = TestBed.inject(HttpTestingController);
      http.match(req => req.url.endsWith('schema-tree'))
        .forEach(req => req.flush({schemas: [{name: 'app'}, {name: 'mysql'}]}));
      // Positional, in the order the schemas were sent: app then mysql
      // (sqlx-3ai).
      http.match(req => req.url.endsWith('tags/targets'))
        .forEach(req => req.flush([[], [{name: 'system'}]]));
      // tables carrying an excluded tag; none here, the schema tag is enough
      http.match(req => req.url.includes('table/by-tag') || req.url.includes('tables/tag'))
        .forEach(req => req.flush([]));
      http.match(req => req.url.includes('graph/v2')).forEach(req => req.flush({
        nodes: [
          {schema: 'app', table: 'orders', columnCount: 3, queryCount: 9, warn: false, selfJoin: false, view: false},
          {schema: 'mysql', table: 'user', columnCount: 3, queryCount: 1, warn: false, selfJoin: false, view: false}
        ],
        fkEdges: [], coQueryEdges: [], viewEdges: []
      }));
      fixture.detectChanges();
    }

    /**
     * The gap this closed: excluding `system` on /tables and clicking through
     * to a schema graph used to bring the system tables straight back, so one
     * parameter had two answers depending on where you read it.
     */
    it('drops a node whose schema is excluded, on a schema graph', () => {
      navigateTo({schema: 'app'}, {schemaTagsExclude: 'system'});
      answerScopedGraph();

      expect(component.nodeCount).toBe(1);
    });

    it('draws everything when no scope is set', () => {
      navigateTo({schema: 'app'}, {});
      loadSchemaTree();
      answerGraph();

      expect(component.nodeCount).toBe(0);
    });

    /** A scope change is a scope change — it has to refetch, not redraw stale nodes. */
    it('treats a change to the scope as a new scope', () => {
      navigateTo({schema: 'app'}, {});
      loadSchemaTree();
      answerGraph();
      const before = component.scopeKeyForTest;

      navigateTo({schema: 'app'}, {schemaTagsExclude: 'system'});

      expect(component.scopeKeyForTest).not.toEqual(before);
    });
  });

  // ── the chip says what the graph is, not how it was built ──

  it('names the tag on a tag graph', () => {
    navigateTo({}, {tag: 'system'});

    expect(toolbar().textContent).toContain('tag: system');
  });

  it('names the active filters on a search graph rather than saying "workbench results"', () => {
    navigateTo({}, {source: 'search', q: 'orders', fullScan: 'YES'});

    const rendered = toolbar().textContent || '';
    expect(rendered).not.toContain('workbench results');
    expect(rendered).toContain('q');
    expect(rendered).toContain('fullScan');
  });

  it('says so plainly when a search graph has no filters at all', () => {
    navigateTo({}, {source: 'search'});

    expect(component.searchSummary()).toBe('all queries');
  });

  it('summarizes rather than listing every filter', () => {
    navigateTo({}, {source: 'search', q: 'a', fullScan: 'YES', tmpDisk: 'YES', errors: 'YES', warnings: 'YES'});

    expect(component.searchSummary()).toMatch(/\+\d+$/);
  });

  // ── leaving a scope is an act, not a side effect ──

  it('offers a way off a scoped graph', () => {
    navigateTo({}, {tag: 'system'});
    loadSchemaTree();
    answerGraph();

    expect(toolbar().querySelector('.chip-x')).toBeTruthy();
  });

  it('clears the scope to a whole-schema graph, keeping the layer toggles', () => {
    navigateTo({}, {tag: 'system'});
    loadSchemaTree();
    answerGraph();
    component.layers = {...component.layers, co: true};

    component.clearScope();

    const [commands, extras] = (router.navigate as jasmine.Spy).calls.mostRecent().args;
    expect(commands).toEqual(['/graph']);
    expect(extras.queryParams['tag']).toBeFalsy();
    expect(extras.queryParams['source']).toBeFalsy();
    expect(extras.queryParams['var-layers']).toBeTruthy();
  });
});
