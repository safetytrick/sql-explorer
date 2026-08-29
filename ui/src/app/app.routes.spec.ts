import {TestBed} from '@angular/core/testing';
import {Router, provideRouter} from '@angular/router';
import {provideHttpClient} from '@angular/common/http';
import {provideHttpClientTesting} from '@angular/common/http/testing';
import {provideLocationMocks} from '@angular/common/testing';
import {HIGHLIGHT_OPTIONS} from 'ngx-highlightjs';
import {appRoutes} from './app.routes';
import {AnalyzedSqlService} from './analyzed-sql.service';
import {TableLensComponent} from './table-lens/table-lens.component';

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
        AnalyzedSqlService,
        { provide: HIGHLIGHT_OPTIONS, useValue: { coreLibraryLoader: () => import('highlight.js/lib/core'), languages: {} } }
      ]
    });
    router = TestBed.inject(Router);
  });

  /** The component the URL settles on, following any redirect. */
  function matched(): unknown {
    return router.routerState.snapshot.root.firstChild?.routeConfig?.component;
  }

  it('sends a column path to the lens, rewritten to the canonical ?columns= URL', async () => {
    await router.navigateByUrl('/table/app_other/user_profile/user_id');

    expect(matched()).toBe(TableLensComponent);
    expect(router.url).toBe('/table/app_other/user_profile?columns=app_other.user_profile.user_id');
  });

  it('keeps the rest of the filter when a column link is followed from a filtered view', async () => {
    await router.navigateByUrl('/table/app_other/user_profile/user_id?activity=ACTIVE');

    expect(router.url).toContain('activity=ACTIVE');
    expect(router.url).toContain('columns=app_other.user_profile.user_id');
  });

  /**
   * These two used to render `TableViewComponent`. It is retired, but they
   * still must not fall through to `table/:schema/:name/:column` — that
   * matches them perfectly well and would send `/queries` to the column
   * drill-down as if `queries` were a column name, producing a confident and
   * entirely wrong page. Declaration order is the only thing preventing it.
   */
  it('redirects the retired queries route to the lens rather than reading "queries" as a column', async () => {
    await router.navigateByUrl('/table/app_other/user_profile/queries');

    expect(matched()).toBe(TableLensComponent);
    expect(router.url).toBe('/table/app_other/user_profile');
  });

  it('redirects the retired join route to the lens scoped to both tables', async () => {
    await router.navigateByUrl('/table/app_other/group_member/join/app_other/groups');

    expect(matched()).toBe(TableLensComponent);
    // ?tables=a,b + tableMode=ALL is what the lens's own join toggle writes
    expect(router.url).toBe('/table/app_other/group_member?tables=app_other.group_member,app_other.groups&tableMode=ALL');
  });

  it('keeps the retired routes ahead of the column drill-down', async () => {
    // the failure this guards is silent: :column would match and redirect to
    // ?columns=app_other.user_profile.queries, which renders a normal-looking page
    await router.navigateByUrl('/table/app_other/user_profile/queries');
    expect(router.url).not.toContain('columns=');
  });

  it('leaves the bare table route alone', async () => {
    await router.navigateByUrl('/table/app_other/user_profile');

    expect(matched()).toBe(TableLensComponent);
    expect(router.url).toBe('/table/app_other/user_profile');
  });
});
