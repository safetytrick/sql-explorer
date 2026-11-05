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
