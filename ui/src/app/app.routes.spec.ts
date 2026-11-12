import {TestBed} from '@angular/core/testing';
import {Router, provideRouter} from '@angular/router';
import {provideHttpClient} from '@angular/common/http';
import {provideHttpClientTesting} from '@angular/common/http/testing';
import {provideLocationMocks} from '@angular/common/testing';
import {appRoutes} from './app.routes';
import {AnalyzedSqlService} from './analyzed-sql.service';
import {TableDdlComponent} from './table-ddl/table-ddl.component';
import {TableProfileComponent} from './table-profile/table-profile.component';
import {TableLensComponent} from './table-lens/table-lens.component';
import {NotFoundComponent} from './not-found/not-found.component';
import {McpSetupComponent} from './mcp-setup/mcp-setup.component';

/**
 * Route *matching*, asserted without rendering anything: `/table/:schema/:name/:column`
 * is a catch-all shape that will happily swallow `/queries` and `/join/**`, and
 * the only thing keeping it from doing so is declaration order.
 */
describe('appRoutes: the table drill-downs', () => {

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

  /** The component the URL settles on, following any redirect. */
  function matched(): unknown {
    return router.routerState.snapshot.root.firstChild?.routeConfig?.component;
  }

  it('sends a column path to the lens, rewritten to the canonical ?columns= URL', async () => {
    await router.navigateByUrl('/table/app_main/user_profile/user_id');

    expect(matched()).toBe(TableLensComponent);
    expect(router.url).toBe('/table/app_main/user_profile?columns=app_main.user_profile.user_id');
  });

  it('keeps the rest of the filter when a column link is followed from a filtered view', async () => {
    await router.navigateByUrl('/table/app_main/user_profile/user_id?activity=ACTIVE');

    expect(router.url).toContain('activity=ACTIVE');
    expect(router.url).toContain('columns=app_main.user_profile.user_id');
  });

  /**
   * These two used to render `TableViewComponent`. It is retired, but they
   * still must not fall through to `table/:schema/:name/:column` — that
   * matches them perfectly well and would send `/queries` to the column
   * drill-down as if `queries` were a column name, producing a confident and
   * entirely wrong page. Declaration order is the only thing preventing it.
   */
  it('redirects the retired queries route to the lens rather than reading "queries" as a column', async () => {
    await router.navigateByUrl('/table/app_main/user_profile/queries');

    expect(matched()).toBe(TableLensComponent);
    expect(router.url).toBe('/table/app_main/user_profile');
  });

  it('redirects the retired join route to the lens scoped to both tables', async () => {
    await router.navigateByUrl('/table/app_main/group_member/join/app_main/user_group');

    expect(matched()).toBe(TableLensComponent);
    // ?tables=a,b + tableMode=ALL is what the lens's own join toggle writes
    expect(router.url).toBe('/table/app_main/group_member?tables=app_main.group_member,app_main.user_group&tableMode=ALL');
  });

  it('opens the change page rather than reading "ddl" as a column', async () => {
    await router.navigateByUrl('/table/app_main/user_profile/ddl?var-op=add-index');

    expect(matched()).toBe(TableDdlComponent);
    expect(router.url).toBe('/table/app_main/user_profile/ddl?var-op=add-index');
  });

  it('opens the profile page rather than reading "profile" as a column', async () => {
    await router.navigateByUrl('/table/app_main/user_profile/profile?var-run=3');

    expect(matched()).toBe(TableProfileComponent);
    expect(router.url).toBe('/table/app_main/user_profile/profile?var-run=3');
  });

  it('keeps the retired routes ahead of the column drill-down', async () => {
    // the failure this guards is silent: :column would match and redirect to
    // ?columns=app_main.user_profile.queries, which renders a normal-looking page
    await router.navigateByUrl('/table/app_main/user_profile/queries');
    expect(router.url).not.toContain('columns=');
  });

  describe('the catch-all', () => {

    it('renders the not-found page instead of an empty outlet', async () => {
      await router.navigateByUrl('/no/such/place');

      expect(matched()).toBe(NotFoundComponent);
      // the address that missed survives — it is what makes a bad link findable
      expect(router.url).toBe('/no/such/place');
    });

    it('does not swallow a real route', async () => {
      await router.navigateByUrl('/tables');
      expect(matched()).not.toBe(NotFoundComponent);

      await router.navigateByUrl('/queries');
      expect(matched()).not.toBe(NotFoundComponent);

      await router.navigateByUrl('/table/app_main/user_profile');
      expect(matched()).toBe(TableLensComponent);

      await router.navigateByUrl('/admin/mcp');
      expect(matched()).toBe(McpSetupComponent);
    });

    // the by-tag pages became ?tags= on the list pages; their links still land (sqlx-m0j.2)
    it('sends a by-tag address to the list page, filtered by the tag', async () => {
      await router.navigateByUrl('/tables/by-tag/billing');
      expect(router.url).toBe('/tables?tags=billing&tagMode=ALL');
    });

    it('sends a schemas by-tag address to Schemas, keeping other params', async () => {
      await router.navigateByUrl('/schemas/by-tag/billing?env=staging');
      expect(router.url).toBe('/schemas?env=staging&tags=billing&tagMode=ALL');
    });

    it('sends a queries by-tag address to the workbench', async () => {
      await router.navigateByUrl('/analyzed-sql/by-tag/billing');
      expect(router.url).toBe('/queries?tags=billing');
    });

    it('still sends the bare root to the starting page, /schemas', async () => {
      await router.navigateByUrl('/');

      expect(matched()).not.toBe(NotFoundComponent);
      expect(router.url).toBe('/schemas');
    });
  });

  it('leaves the bare table route alone', async () => {
    await router.navigateByUrl('/table/app_main/user_profile');

    expect(matched()).toBe(TableLensComponent);
    expect(router.url).toBe('/table/app_main/user_profile');
  });
});
