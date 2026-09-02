import {TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {RouterTestingHarness} from '@angular/router/testing';
import {HttpTestingController} from '@angular/common/http/testing';
import {TableLensComponent} from './table-lens.component';
import {COMMON_TEST_PROVIDERS} from '../testing/common-test-providers';
import {ColumnUsage, TableMeta, TableStats} from '../schema-lens.service';
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
    // minute precision — the raw LocalDateTime this used to assert was 26
    // characters and overflowed the 190px rail these metrics sit in
    expect(rendered.get('first seen')).toBe('2026-01-02 03:04');
    expect(rendered.get('last seen')).toBe('2026-08-01 00:00');
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

/**
 * A table that does not exist used to render a complete, normal-looking lens:
 * empty Columns/Indexes cards and "no queries reference this table", which is
 * exactly what a real but unused table looks like. `/table/{s}/{t}/{c}` now
 * redirects here, so a mis-parsed column reference (`app_del.links` arriving as
 * `/table/app_del/id?columns=app_del.id.id`) lands on that page routinely.
 *
 * The signal is `/metadata/table/{s}/{t}` answering with a **null body** — not
 * a 404 — which is the one thing worth pinning: a 404 would be an HttpClient
 * error and would collapse "unknown table" into "backend down".
 */
describe('TableLensComponent unknown table', () => {

  const STATS: TableStats = {
    distinctQueries: 0, totalExecutions: 0, totalTimeMs: 0, readExecutions: 0, writeExecutions: 0
  };

  const STRUCTURE: TableMeta = {
    indices: [], type: 'TABLE', remarks: null, columns: null, underlyingTables: null, viewColumns: null
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        ...COMMON_TEST_PROVIDERS,
        provideRouter([{ path: 'table/:schema/:name', component: TableLensComponent }])
      ]
    });
  });

  /** Answer the two requests the verdict depends on; the rest are left pending. */
  async function open(url: string, answers: {
    structure?: TableMeta | null,
    structureFails?: boolean,
    stats?: TableStats,
    columns?: ColumnUsage[]
  }) {
    const harness = await RouterTestingHarness.create(url);
    const http = TestBed.inject(HttpTestingController);
    if (answers.structureFails) {
      http.expectOne(request => /metadata\/table\/[^/]+\/[^/]+$/.test(request.url))
        .flush('boom', { status: 500, statusText: 'Server Error' });
    } else if (answers.structure !== undefined) {
      http.expectOne(request => /metadata\/table\/[^/]+\/[^/]+$/.test(request.url))
        .flush(answers.structure);
    }
    if (answers.stats) {
      http.expectOne(request => request.url.endsWith('/stats')).flush(answers.stats);
    }
    if (answers.columns) {
      http.expectOne(request => request.url.endsWith('/columns')).flush(answers.columns);
    }
    harness.detectChanges();
    return {
      harness,
      component: harness.routeDebugElement!.componentInstance as TableLensComponent,
      element: harness.routeNativeElement!
    };
  }

  function column(name: string): ColumnUsage {
    return {
      name, roles: ['join'], type: 'int', size: 11, nullable: false, autoIncrement: false,
      primaryKey: false, foreignKey: false, characterSet: null, collation: null, queries: 3
    };
  }

  it('reports not found when the metadata answers null and no query touches the table', async () => {
    const {component, element} = await open('/table/app_del/id', { structure: null, stats: STATS });

    expect(component.notFound()).toBe(true);
    expect(element.querySelector('.lens-card.not-found')).toBeTruthy();
    // the cards that would otherwise make false statements are simply absent
    expect(element.querySelector('.lens-grid')).toBeNull();
    expect(element.textContent).not.toContain('no queries reference this table');
    expect(element.textContent).not.toContain('no foreign keys');
  });

  /**
   * Against a CSV/Parquet source there is no schema to introspect, so *every*
   * table answers null. The query count is the second half of the conjunction
   * that keeps this from turning the whole app into a not-found page.
   */
  it('keeps the page for a table the schema lacks but queries reference', async () => {
    const {component, element} = await open('/table/app_other/user_profile', {
      structure: null,
      stats: { ...STATS, distinctQueries: 7 }
    });

    expect(component.notFound()).toBe(false);
    expect(component.unintrospected()).toBe(true);
    expect(element.querySelector('.lens-grid')).toBeTruthy();
    expect(element.querySelector('.type-badge.b-unknown')!.textContent).toContain('not in schema');
  });

  it('does not claim absence while the metadata is still in flight', async () => {
    const {component, element} = await open('/table/app_other/user_profile', { stats: STATS });

    expect(component.notFound()).toBe(false);
    expect(element.querySelector('.lens-card.not-found')).toBeNull();
  });

  /** An outage is not an absence — the metadata failing tells us nothing. */
  it('does not claim absence when the metadata request fails', async () => {
    const {component} = await open('/table/app_other/user_profile', { structureFails: true, stats: STATS });

    expect(component.metadataFailed).toBe(true);
    expect(component.notFound()).toBe(false);
  });

  it('leaves a real table alone', async () => {
    const {component} = await open('/table/app_other/user_profile', { structure: STRUCTURE, stats: STATS });

    expect(component.notFound()).toBe(false);
    expect(component.unintrospected()).toBe(false);
  });

  it('flags a drill-down naming a column the table does not have', async () => {
    const {component, element} = await open('/table/app_other/user_profile?columns=app_other.user_profile.nope', {
      structure: STRUCTURE, stats: STATS, columns: [column('user_id')]
    });

    expect(component.drillColumnUnknown()).toBe(true);
    expect(element.querySelector('.drill-chip.warn')!.textContent).toContain('not a column of this table');
  });

  it('flags a drill-down whose reference belongs to another table', async () => {
    const {component} = await open('/table/app_other/user_profile?columns=app_other.groups.user_id', {
      structure: STRUCTURE, stats: STATS, columns: [column('user_id')]
    });

    expect(component.drillColumnUnknown()).toBe(true);
  });

  it('accepts a real column whatever its case', async () => {
    const {component} = await open('/table/app_other/user_profile?columns=app_other.user_profile.USER_ID', {
      structure: STRUCTURE, stats: STATS, columns: [column('user_id')]
    });

    expect(component.drillColumnUnknown()).toBe(false);
  });

  /** With no column list there is nothing to check against, so make no claim. */
  it('stays quiet about the drill column when no metadata is available', async () => {
    const {component} = await open('/table/app_other/user_profile?columns=app_other.user_profile.anything', {
      structure: null, stats: { ...STATS, distinctQueries: 4 }, columns: []
    });

    expect(component.drillColumnUnknown()).toBe(false);
  });

  it('says the filter matched nothing rather than blaming the table', async () => {
    const {harness, element} = await open('/table/app_other/user_profile?columns=app_other.user_profile.user_id', {
      structure: STRUCTURE, stats: STATS, columns: [column('user_id')]
    });
    const http = TestBed.inject(HttpTestingController);
    http.expectOne(request => request.url.endsWith('queries/search'))
      .flush({ total: 0, results: [], facets: { tables: {}, schemas: {} } });
    harness.detectChanges();

    expect(element.textContent).toContain('no queries match this filter');
    expect(element.textContent).not.toContain('no queries reference this table');
  });
});

/**
 * The schema's own comments on a table's columns.
 *
 * <p>The lens's column table is built from `ColumnUsage`, which carries no
 * comment; the comment arrives on the metadata payload the lens already fetches
 * for the table comment, so the two are joined by name (sqlx-gw5).
 */
describe('TableLensComponent — column comments', () => {

  const usage = (name: string) => ({
    name, type: 'int', size: 11, nullable: false, autoIncrement: false,
    primaryKey: false, foreignKey: false, characterSet: null, collation: null,
    roles: [], queries: 0
  });

  function lensWith(columns: {columnName: string; remarks: string | null}[]) {
    const component = Object.create(TableLensComponent.prototype) as TableLensComponent;
    // The same assignment the metadata subscribe performs.
    (component as unknown as {columnComments: Map<string, string>}).columnComments =
      new Map(columns
        .filter(column => (column.remarks || '').trim())
        .map(column => [column.columnName.toLowerCase(), column.remarks!.trim()]));
    return component;
  }

  it('shows the comment the schema carries for a column', () => {
    const lens = lensWith([{columnName: 'id', remarks: 'surrogate key'}]);

    expect(lens.commentFor(usage('id'))).toBe('surrogate key');
  });

  /**
   * MySQL returns the EMPTY STRING for "no comment", not null, and it reaches
   * the client unchanged — so without this every uncommented column would get
   * a blank line under its name.
   */
  it('treats an empty comment as absent', () => {
    const lens = lensWith([{columnName: 'total', remarks: ''},
                           {columnName: 'name', remarks: '   '},
                           {columnName: 'note', remarks: null}]);

    expect(lens.commentFor(usage('total'))).toBeNull();
    expect(lens.commentFor(usage('name'))).toBeNull();
    expect(lens.commentFor(usage('note'))).toBeNull();
  });

  /** Joined by name, and MySQL identifiers differ in case across sources. */
  it('matches a column whatever case the metadata used', () => {
    const lens = lensWith([{columnName: 'Created_On', remarks: 'when the row appeared'}]);

    expect(lens.commentFor(usage('created_on'))).toBe('when the row appeared');
  });

  it('has nothing to say about a column the schema did not comment', () => {
    const lens = lensWith([{columnName: 'id', remarks: 'surrogate key'}]);

    expect(lens.commentFor(usage('member_id'))).toBeNull();
  });
});
