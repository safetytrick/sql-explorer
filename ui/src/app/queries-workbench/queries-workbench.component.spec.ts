import {TestBed} from '@angular/core/testing';
import {HttpTestingController} from '@angular/common/http/testing';
import {provideRouter, Router} from '@angular/router';
import {RouterTestingHarness} from '@angular/router/testing';
import {QueriesWorkbenchComponent} from './queries-workbench.component';
import {QueryScopeService} from '../query-scope.service';
import {COMMON_TEST_PROVIDERS} from '../testing/common-test-providers';
import type {QueryHit} from '../query-search.service';

/**
 * The workbench must seed the schema-tree scope from a shared URL, and show
 * the graph of its search in place of the list (see search-url.ts for the
 * shared vocabulary).
 */
describe('QueriesWorkbenchComponent URL state', () => {

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        ...COMMON_TEST_PROVIDERS,
        provideRouter([{ path: 'queries', component: QueriesWorkbenchComponent }])
      ]
    });
  });

  async function open(url: string): Promise<{ component: QueriesWorkbenchComponent, harness: RouterTestingHarness }> {
    const harness = await RouterTestingHarness.create(url);
    return { component: harness.routeDebugElement!.componentInstance, harness };
  }

  it('seeds the schema-tree scope service from a shared link', async () => {
    await open('/queries?q=select&schemas=tdb,app_main&tables=tdb.member_session&tableMode=ALL'
      + '&schemaTags=core&schemaTagsExclude=legacy&schemaTagMode=ALL');
    const scopeService = TestBed.inject(QueryScopeService);
    expect(scopeService.scope.schemas).toEqual(['tdb', 'app_main']);
    expect(scopeService.scope.tables).toEqual(['tdb.member_session']);
    expect(scopeService.scope.tableMode).toBe('ALL');
    expect(scopeService.schemaTagFilter).toEqual({ includeTags: ['core'], excludeTags: ['legacy'], tagMode: 'ALL' });
  });

  it('keeps in-session tree scope when the URL carries no scope params', async () => {
    const scopeService = TestBed.inject(QueryScopeService);
    scopeService.setScope({ schemas: ['tdb'], tables: [], tableMode: 'ANY' });
    await open('/queries?q=select');
    expect(scopeService.scope.schemas).toEqual(['tdb']);
  });

  it('reflects later tree-scope changes back into the URL', async () => {
    const { harness } = await open('/queries');
    TestBed.inject(QueryScopeService).toggleSchema('tdb');
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toContain('schemas=tdb');
  });

  it('shows the graph in place of the list, keeping the search in the URL', async () => {
    const { component, harness } = await open('/queries?q=select&tables=app_main.account');
    component.setGraphView(true);
    await harness.fixture.whenStable();
    harness.fixture.detectChanges();
    const router = TestBed.inject(Router);
    const params = router.parseUrl(router.url).queryParams;
    expect(params['view']).toBe('graph');
    expect(params['q']).toBe('select');
    expect(params['tables']).toBe('app_main.account');
    expect(harness.routeNativeElement!.querySelector('app-graph-lens')).not.toBeNull();
    expect(harness.routeNativeElement!.querySelector('.query-list')).toBeNull();
  });

  it('keeps the graph\'s own params while it shows, and drops them with it', async () => {
    const { component, harness } = await open('/queries?q=select&view=graph&var-layers=fk&var-table=account');
    expect(component.graphView).toBeTrue();
    component.queryRegex = 'insert';
    component.filtersChanged();
    await harness.fixture.whenStable();
    const router = TestBed.inject(Router);
    let params = router.parseUrl(router.url).queryParams;
    expect(params['q']).toBe('insert');
    expect(params['var-layers']).toBe('fk');
    expect(params['var-table']).toBe('account');

    component.setGraphView(false);
    await harness.fixture.whenStable();
    params = router.parseUrl(router.url).queryParams;
    expect(params['view']).toBeUndefined();
    expect(params['var-layers']).toBeUndefined();
  });

  it('reads the filters again when a link lands on the page it is already on', async () => {
    // the graph links an edge to the queries joining its two tables: this page, other filters
    const { component, harness } = await open('/queries?q=select&view=graph');
    await harness.navigateByUrl('/queries?tables=app_main.account,app_main.site&tableMode=ALL');
    expect(component.queryRegex).toBe('');
    expect(component.graphView).toBeFalse();
    expect(TestBed.inject(QueryScopeService).scope.tables).toEqual(['app_main.account', 'app_main.site']);
  });

  /** A hit as far as expansion reads it: the digest is its identity in the URL. */
  function hit(digest: string): QueryHit {
    return { profile: { digest } } as unknown as QueryHit;
  }

  it('seeds expanded rows from var-expanded', async () => {
    const { component } = await open('/queries?q=select&var-expanded=v3:aaa,v3:bbb');
    expect(component.isExpanded(hit('v3:aaa'))).toBeTrue();
    expect(component.isExpanded(hit('v3:bbb'))).toBeTrue();
    expect(component.isExpanded(hit('v3:ccc'))).toBeFalse();
  });

  it('writes an expanded row to the URL beside the search, and drops it when collapsed', async () => {
    const { component, harness } = await open('/queries?q=select');
    const router = TestBed.inject(Router);

    component.toggleExpanded(hit('v3:aaa'));
    await harness.fixture.whenStable();
    const expandedTree = router.parseUrl(router.url);
    expect(expandedTree.queryParams['var-expanded']).toBe('v3:aaa');
    expect(expandedTree.queryParams['q']).toBe('select');

    component.toggleExpanded(hit('v3:aaa'));
    await harness.fixture.whenStable();
    expect(router.parseUrl(router.url).queryParams['var-expanded']).toBeUndefined();
  });

  it('full-SQL mode expands every row, and a caret then collapses one', async () => {
    const { component, harness } = await open('/queries?q=select&var-sql=full');
    const router = TestBed.inject(Router);
    expect(component.isExpanded(hit('v3:aaa'))).toBeTrue();
    expect(component.isExpanded(hit('v3:bbb'))).toBeTrue();

    component.toggleExpanded(hit('v3:aaa'));
    await harness.fixture.whenStable();
    expect(component.isExpanded(hit('v3:aaa'))).toBeFalse();
    expect(router.parseUrl(router.url).queryParams['var-expanded']).toBe('v3:aaa');

    component.setFullSql(false);
    await harness.fixture.whenStable();
    expect(component.isExpanded(hit('v3:bbb'))).toBeFalse();
    const params = router.parseUrl(router.url).queryParams;
    expect(params['var-sql']).toBeUndefined();
    expect(params['var-expanded']).toBeUndefined();
  });

  // The next page loads as the list end scrolls near, so it must not be asked
  // for twice, and a page for a superseded search must not be appended (sqlx-ywy.6).
  describe('loading the next page', () => {
    const page = (from: number, count: number, total: number) => ({
      total, facets: {tables: {}, schemas: {}},
      results: Array.from({length: count}, (_, at) => ({profile: {id: `q${from + at}`, digest: `v3:${from + at}`}, tags: []}))
    });
    // the first search is debounced (250ms); let it go out before answering it
    const firstSearch = () => new Promise(resolve => setTimeout(resolve, 300));

    it('asks once however often the end comes into view', async () => {
      const { component } = await open('/queries');
      await firstSearch();
      const http = TestBed.inject(HttpTestingController);
      http.match(r => r.url.endsWith('queries/search')).forEach(r => r.flush(page(0, 50, 120)));

      component.loadMore();
      component.loadMore();
      const more = http.match(r => r.url.endsWith('queries/search'));
      expect(more.length).toBe(1);
      expect(more[0].request.body.offset).toBe(50);
      more[0].flush(page(50, 50, 120));
      expect(component.hits.length).toBe(100);
    });

    it('drops a page that arrives after the search changed', async () => {
      const { component } = await open('/queries');
      await firstSearch();
      const http = TestBed.inject(HttpTestingController);
      http.match(r => r.url.endsWith('queries/search')).forEach(r => r.flush(page(0, 50, 120)));

      component.loadMore();
      const stale = http.match(r => r.url.endsWith('queries/search'));
      expect(stale.length).toBe(1);
      (component as unknown as {runSearch: () => void}).runSearch();
      const fresh = http.match(r => r.url.endsWith('queries/search'));
      fresh.forEach(r => r.flush(page(0, 10, 10)));
      stale.forEach(r => r.flush(page(50, 50, 120)));

      expect(component.hits.length).toBe(10);
      expect(component.total).toBe(10);
    });
  });

  it('explains every analyzer filter on hover (sqlx-ywy.4)', async () => {
    const { harness } = await open('/queries');
    const labels = Array.from((harness.routeNativeElement as HTMLElement).querySelectorAll('.check-row'))
      .filter(label => /analy|failure/.test(label.textContent ?? ''));
    expect(labels.length).toBe(6);
    labels.forEach(label => expect(label.getAttribute('title')?.length).toBeGreaterThan(20));
  });

  // one-click, log-spaced bounds; the same click clears it (sqlx-ywy.3)
  it('applies a selectivity preset, writes it to the URL, and clears it on a second click', async () => {
    const { component, harness } = await open('/queries');
    const router = TestBed.inject(Router);
    const tenth = component.rangePresets.selectivityPct.find(preset => preset.label === '<0.1%')!;

    component.togglePreset('selectivityPct', tenth);
    await harness.fixture.whenStable();
    expect(component.presetOn('selectivityPct', tenth)).toBeTrue();
    expect(router.parseUrl(router.url).queryParams['selMax']).toBe('0.1');

    component.togglePreset('selectivityPct', tenth);
    await harness.fixture.whenStable();
    expect(component.selectivityPct).toEqual({min: null, max: null});
    expect(router.parseUrl(router.url).queryParams['selMax']).toBeUndefined();
  });

  it('expanding a row does not also select it', async () => {
    const { component } = await open('/queries');
    const event = new Event('click');
    const stop = spyOn(event, 'stopPropagation');
    component.toggleExpanded(hit('v3:aaa'), event);
    expect(stop).toHaveBeenCalled();
  });

  it('seeds the index-hint filter from a link and writes a change back to the URL', async () => {
    const { component, harness } = await open('/queries?hinted=FORCE');
    expect(component.hinted).toBe('FORCE');
    expect(component.activeFilterChips()).toContain('index hint: force');

    component.setHinted('ANY');
    await harness.fixture.whenStable();
    const router = TestBed.inject(Router);
    expect(router.parseUrl(router.url).queryParams['hinted']).toBe('ANY');

    component.setHinted(null);
    await harness.fixture.whenStable();
    expect(router.parseUrl(router.url).queryParams['hinted']).toBeUndefined();
  });
});
