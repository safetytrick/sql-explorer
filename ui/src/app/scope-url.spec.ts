import {TestBed} from '@angular/core/testing';
import {provideRouter, Router} from '@angular/router';
import {provideLocationMocks} from '@angular/common/testing';
import {provideHttpClient} from '@angular/common/http';
import {provideHttpClientTesting} from '@angular/common/http/testing';
import {appRoutes} from './app.routes';
import {AnalyzedSqlService} from './analyzed-sql.service';
import {droppedScope, SCOPE_PARAMS, scopeOf} from './scope-url';

describe('scopeOf / droppedScope', () => {

  it('keeps only the parameters that name corpus objects', () => {
    const scope = scopeOf({
      schemaTagsExclude: 'system', schemas: 'app',
      // page-local: must not travel
      q: 'orders', fullScan: 'YES', tags: 'core', tagMode: 'ALL', 'var-layers': 'fk', sort: 'AVG_TIME'
    });

    expect(Object.keys(scope).sort()).toEqual(['schemaTagsExclude', 'schemas']);
  });

  /**
   * `tags` and `tagMode` are the sharp case: shared names that filter queries
   * on /queries and tables on /tables, with different defaults. Carrying them
   * would silently ask a different question on arrival.
   */
  it('never travels the shared tag filter names', () => {
    expect(SCOPE_PARAMS as readonly string[]).not.toContain('tags');
    expect(SCOPE_PARAMS as readonly string[]).not.toContain('tagMode');
  });

  it('treats an empty value as unset', () => {
    expect(scopeOf({schemaTagsExclude: ''})).toEqual({});
  });

  it('reports only what the destination is missing', () => {
    expect(droppedScope({schemaTagsExclude: 'system', schemas: 'app'}, {schemas: 'del'}))
      .toEqual({schemaTagsExclude: 'system'});
  });

  it('reports nothing when the destination already has it', () => {
    expect(droppedScope({schemaTagsExclude: 'system'}, {schemaTagsExclude: 'system'})).toEqual({});
  });
});

/**
 * The guard against the real route table, driven through the real router —
 * the behaviour only exists in the interaction between the two.
 */
describe('carryScope, across real navigations', () => {

  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(appRoutes),
        provideLocationMocks(),
        provideHttpClient(),
        provideHttpClientTesting(),
        AnalyzedSqlService
      ]
    });
    router = TestBed.inject(Router);
  });

  function url(): string {
    return router.url;
  }

  it('carries the scope onto a page that did not ask for it', async () => {
    await router.navigateByUrl('/queries?schemaTagsExclude=system');

    await router.navigateByUrl('/tables');

    expect(url()).toContain('schemaTagsExclude=system');
  });

  it('carries it again on the next hop, not just the first', async () => {
    await router.navigateByUrl('/queries?schemaTagsExclude=system');
    await router.navigateByUrl('/tables');

    await router.navigateByUrl('/schemas');

    expect(url()).toContain('schemaTagsExclude=system');
  });

  /** A link that names a scope explicitly must win over the one you arrived with. */
  it('lets the destination override what it was carrying', async () => {
    await router.navigateByUrl('/queries?schemaTagsExclude=system');

    await router.navigateByUrl('/tables?schemaTagsExclude=clickhouse');

    expect(url()).toContain('schemaTagsExclude=clickhouse');
    expect(url()).not.toContain('system');
  });

  /**
   * The whole reason the guard is safe. Clearing happens on the page you are
   * already on, and Angular's default runGuardsAndResolvers is `paramsChange`,
   * which excludes query parameters — so the guard does not re-run and cannot
   * put the scope back. Switch any route to a `…QueryParamsChange` variant and
   * this fails, which is the point of asserting it.
   */
  it('does not resurrect a scope cleared on the page you are on', async () => {
    await router.navigateByUrl('/tables?schemaTagsExclude=system');

    await router.navigateByUrl('/tables');

    expect(url()).not.toContain('schemaTagsExclude');
  });

  it('leaves page-local parameters behind', async () => {
    await router.navigateByUrl('/queries?q=orders&fullScan=YES&schemaTagsExclude=system');

    await router.navigateByUrl('/tables');

    expect(url()).toContain('schemaTagsExclude=system');
    expect(url()).not.toContain('fullScan');
    expect(url()).not.toContain('q=orders');
  });

  it('does nothing at all when no scope is set', async () => {
    await router.navigateByUrl('/queries?q=orders');

    await router.navigateByUrl('/tables');

    expect(url()).toBe('/tables');
  });


  /**
   * The exact hop Michael reported: /queries to a table lens, which is how you
   * follow a query to the table it touches (`app-column-link` and the SQL
   * view's table links both route here).
   */
  it('carries the scope from the workbench to a table lens', async () => {
    await router.navigateByUrl('/queries?schemaTagsExclude=system');

    await router.navigateByUrl('/table/app/orders');

    expect(url()).toContain('schemaTagsExclude=system');
  });

  /** The column drill-down is a redirect, so the guard cannot run on it. */
  it('carries the scope through the column redirect too', async () => {
    await router.navigateByUrl('/queries?schemaTagsExclude=system');

    await router.navigateByUrl('/table/app/orders/total');

    expect(url()).toContain('schemaTagsExclude=system');
    expect(url()).toContain('columns=app.orders.total');
  });

  it('carries the scope onto a single query', async () => {
    await router.navigateByUrl('/queries?schemaTagsExclude=system');

    await router.navigateByUrl('/analyzed-sql/abc123');

    expect(url()).toContain('schemaTagsExclude=system');
  });

  /** Returning a UrlTree restarts navigation; if it did not terminate, this hangs. */
  it('settles rather than redirecting forever', async () => {
    await router.navigateByUrl('/queries?schemaTagsExclude=system');

    await expectAsync(router.navigateByUrl('/graph')).toBeResolvedTo(true);
    expect(url()).toContain('/graph');
  });
});
