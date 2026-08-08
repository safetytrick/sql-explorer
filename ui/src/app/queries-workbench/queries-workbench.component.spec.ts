import {TestBed} from '@angular/core/testing';
import {provideRouter, Router} from '@angular/router';
import {RouterTestingHarness} from '@angular/router/testing';
import {QueriesWorkbenchComponent} from './queries-workbench.component';
import {QueryScopeService} from '../query-scope.service';
import {COMMON_TEST_PROVIDERS} from '../testing/common-test-providers';

/**
 * The workbench must seed the schema-tree scope from a shared URL and
 * translate the whole search into a shareable /graph link (see
 * search-url.ts for the shared vocabulary).
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

  it('graph results is a pure URL translation carrying the full search', async () => {
    const { component } = await open('/queries?q=select&tables=tdb.member_session&schemaTags=core');
    const router = TestBed.inject(Router);
    const navigate = spyOn(router, 'navigate');
    component.graphResults();
    // Asserted against the recorded args rather than with
    // `jasmine.objectContaining` — that matcher throws
    // "ReferenceError: i is not defined" under this Karma/Jasmine build.
    // Exact equality is the stronger check anyway: defaults must not leak
    // into the link, so these four params are the whole query string.
    expect(navigate).toHaveBeenCalledTimes(1);
    const [commands, extras] = navigate.calls.mostRecent().args;
    expect(commands).toEqual(['/graph']);
    expect(extras!.queryParams).toEqual({
      source: 'search',
      q: 'select',
      tables: 'tdb.member_session',
      schemaTags: 'core'
    });
  });
});
