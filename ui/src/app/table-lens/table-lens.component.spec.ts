import {TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {RouterTestingHarness} from '@angular/router/testing';
import {HttpTestingController} from '@angular/common/http/testing';
import {TableLensComponent} from './table-lens.component';
import {COMMON_TEST_PROVIDERS} from '../testing/common-test-providers';
import {ColumnUsage} from '../schema-lens.service';
import {QueryHit} from '../query-search.service';

/**
 * The lens's drill-downs and collapse state are URL state: a shared link has
 * to reproduce the view, which means reading them back out of the params.
 */
describe('TableLensComponent drill-downs', () => {

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        ...COMMON_TEST_PROVIDERS,
        provideRouter([{ path: 'table/:schema/:name', component: TableLensComponent }])
      ]
    });
  });

  async function open(url: string): Promise<TableLensComponent> {
    const harness = await RouterTestingHarness.create(url);
    return harness.routeDebugElement!.componentInstance;
  }

  function column(name: string, roles: string[]): ColumnUsage {
    return {
      name, roles, type: 'int', size: 11, nullable: false, autoIncrement: false,
      primaryKey: false, foreignKey: false, characterSet: null, collation: null, queries: 3
    };
  }

  it('starts with no drill-down and both relationship cards collapsed', async () => {
    const component = await open('/table/app_other/user_profile');

    expect(component.drillColumn).toBeNull();
    expect(component.joinWith).toBeNull();
    expect(component.fkOpen).toBe(false);
    expect(component.coOpen).toBe(false);
  });

  it('reads a column drill-down out of a shared link', async () => {
    const component = await open('/table/app_other/user_profile?columns=app_other.user_profile.user_id');

    expect(component.drillColumn).toBe('app_other.user_profile.user_id');
  });

  it('reads a join drill-down, ignoring the table itself among the scope', async () => {
    const component = await open('/table/app_other/group_member?tables=app_other.group_member,app_other.groups&tableMode=ALL');

    expect(component.joinWith).toBe('app_other.groups');
  });

  it('restores expanded relationship cards from the presentation params', async () => {
    const component = await open('/table/app_other/user_profile?var-fk=1&var-co=1');

    expect(component.fkOpen).toBe(true);
    expect(component.coOpen).toBe(true);
  });

  /**
   * Only a column that joins or filters can constrain a query, so only those
   * drill — a select-only column would open an empty list that reads as broken.
   */
  it('offers the drill-down only for join and criteria columns', async () => {
    const component = await open('/table/app_other/user_profile');

    expect(component.isDrillable(column('user_id', ['join']))).toBe(true);
    expect(component.isDrillable(column('status', ['criteria']))).toBe(true);
    expect(component.isDrillable(column('email', ['criteria', 'select']))).toBe(true);
    expect(component.isDrillable(column('twitter_link', ['select']))).toBe(false);
    expect(component.isDrillable(column('unused', []))).toBe(false);
  });

  it('translates the view into workbench params without re-mapping anything', async () => {
    const component = await open('/table/app_other/user_profile?columns=app_other.user_profile.user_id');
    const params = component.workbenchParams();

    expect(params['columns']).toBe('app_other.user_profile.user_id');
    expect(params['tables']).toBe('app_other.user_profile');
  });
});

/**
 * The queries card carries the same six values the SQL view does — status,
 * first seen, last seen, executed, impact and avg scanned — rendered by the
 * shared `app-sql-summary` rather than a third hand-rolled copy of them.
 * Impact is *total* time; the average the card used to show was neither
 * labelled nor comparable with it.
 */
describe('TableLensComponent query metrics', () => {

  const HIT = {
    profile: {
      id: 'q1',
      digest: 'd1',
      normalizedDigest: 'd1',
      query: 'SELECT id FROM user_profile WHERE user_id = ?',
      type: 'SELECT',
      queryActivityStatus: 'ACTIVE',
      analysisFailed: false,
      analysisFailureReason: null,
      mismatchedJoins: [],
      runtimeMetrics: {
        fullTableScan: true,
        executed: 12345,
        totalTime: 4_000_000_000_000,
        averageTime: 2_000_000_000,
        averageRowsScanned: 4321,
        firstSeen: '2026-01-02T03:04:05',
        lastSeen: '2026-08-01T00:00:00'
      }
    },
    tags: []
  } as unknown as QueryHit;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        ...COMMON_TEST_PROVIDERS,
        provideRouter([{ path: 'table/:schema/:name', component: TableLensComponent }])
      ]
    });
  });

  /** Open the lens and answer only the query search; the card's other fetches pend. */
  async function openWithHits(url: string, hits: QueryHit[]): Promise<HTMLElement> {
    const harness = await RouterTestingHarness.create(url);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne(request => request.url.endsWith('queries/search'))
      .flush({ total: hits.length, results: hits, facets: { tables: {}, schemas: {} } });
    harness.detectChanges();
    return harness.routeNativeElement!;
  }

  it('renders the six per-query metrics in the row', async () => {
    const element = await openWithHits('/table/app_other/user_profile', [HIT]);
    const metrics = element.querySelector('.query-metrics')!;
    const rendered = new Map(Array.from(metrics.querySelectorAll('.metric-row')).map(row => [
      row.querySelector('.metric-key')!.textContent!.trim(),
      row.querySelector('.metric-val')!.textContent!.trim()
    ]));

    expect(metrics.querySelector('app-sql-summary')).toBeTruthy();
    expect(rendered.get('status')).toBe('active');
    expect(rendered.get('first seen')).toBe('2026-01-02T03:04:05');
    expect(rendered.get('last seen')).toBe('2026-08-01T00:00:00');
    expect(rendered.get('executed')).toBe('12,345');
    expect(rendered.get('avg scanned')).toBe('4,321');
    // impact is totalTime — 4e12 ps is 4 seconds, not the 2ms average
    expect(rendered.get('impact')).toBe('4s');
  });

  it('keeps the full-scan badge, which the shared summary does not carry', async () => {
    const element = await openWithHits('/table/app_other/user_profile', [HIT]);

    expect(element.querySelector('.query-row-head .badge')!.textContent).toContain('full scan');
  });
});
