import {TestBed} from '@angular/core/testing';
import {HttpTestingController} from '@angular/common/http/testing';
import {provideRouter, Router} from '@angular/router';
import {RouterTestingHarness} from '@angular/router/testing';
import {ServerCompareComponent} from './server-compare.component';
import {COMMON_TEST_PROVIDERS} from '../testing/common-test-providers';
import type {CompareReport} from './server-compare';

const REPORT: CompareReport = {
  environment: 'env',
  clusters: ['shard1', 'shard2'],
  cluster: 'shard1',
  servers: [
    {name: 'shard1', roles: ['primary'], read: true, windowDays: 10},
    {name: 'shard1-replica-a', roles: ['replica'], read: true, windowDays: 10},
    {name: 'shard1-replica-b', roles: ['replica'], read: false, windowDays: null}
  ],
  by: 'QUERY',
  matched: 5,
  compared: 4,
  flagCounts: {PLAN_DIFFERS: 1, ON_PRIMARY: 1},
  unflagged: 2,
  total: 2,
  rows: [
    {key: 'v3:plan', text: 'select * from account where site_id = ?', kind: 'READ', flags: ['PLAN_DIFFERS'],
      difference: 4.9, secondsPerDay: 5.1, queries: 1, cells: [
        {executions: 100, seconds: 1, secondsPerDay: 0.1, executionsPerDay: 10, rowsExaminedPerExecution: 10,
          msPerExecution: 10, rowsAffected: 0},
        {executions: 100, seconds: 50, secondsPerDay: 5, executionsPerDay: 10, rowsExaminedPerExecution: 5000,
          msPerExecution: 500, rowsAffected: 0},
        null]},
    {key: 'v3:reads', text: 'select id from site', kind: 'READ', flags: ['ON_PRIMARY'],
      difference: 1, secondsPerDay: 2, queries: 1, cells: [
        {executions: 100, seconds: 10, secondsPerDay: 1, executionsPerDay: 10, rowsExaminedPerExecution: 1,
          msPerExecution: 100, rowsAffected: 0},
        {executions: 100, seconds: 10, secondsPerDay: 1, executionsPerDay: 10, rowsExaminedPerExecution: 1,
          msPerExecution: 100, rowsAffected: 0},
        null]}
  ],
  basis: 'how the figures are read'
};

describe('ServerCompareComponent', () => {

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        ...COMMON_TEST_PROVIDERS,
        provideRouter([{path: 'queries', component: ServerCompareComponent}])
      ]
    });
  });

  async function open(url: string) {
    const harness = await RouterTestingHarness.create(url);
    const component: ServerCompareComponent = harness.routeDebugElement!.componentInstance;
    return {harness, component, http: TestBed.inject(HttpTestingController)};
  }

  it('sends the workbench search and its own state, and draws a row per query with the flagged cell marked', async () => {
    const {harness, http} = await open('/queries?q=select&view=servers&var-cluster=shard1&var-flags=plan-differs');
    const request = http.expectOne(req => req.url.endsWith('topology/compare'));
    expect(request.request.method).toBe('POST');
    expect(request.request.body.queryRegex).toBe('select');
    expect(request.request.params.get('cluster')).toBe('shard1');
    expect(request.request.params.get('flags')).toBe('PLAN_DIFFERS');
    expect(request.request.params.get('by')).toBe('QUERY');
    request.flush(REPORT);
    harness.fixture.detectChanges();

    const el = harness.routeNativeElement!;
    const rows = el.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].querySelector('.row-text')?.getAttribute('href')).toBe('/analyzed-sql/v3:plan');
    // the plan differs on replica-a, which examines 5,000 rows an execution
    const cells = rows[0].querySelectorAll('td.cell');
    expect(cells[1].classList).toContain('hot');
    expect(cells[0].classList).not.toContain('hot');
    expect(cells[2].textContent).toContain('—');
    expect(el.querySelector('th.col-server.unread')?.textContent).toContain('not read');
    // a server's header opens the same search, read from that server alone
    expect(el.querySelector('th.col-server a')?.getAttribute('href')).toContain('sources=shard1');
    expect(el.querySelector('.flag-chip.on')?.textContent).toContain('plan differs');
    http.verify();
  });

  it('writes a flag, the rows and show all to the URL, and refetches', async () => {
    const {harness, component, http} = await open('/queries?q=select&view=servers');
    http.expectOne(req => req.url.endsWith('topology/compare')).flush(REPORT);

    component.toggleFlag('ON_PRIMARY');
    await harness.fixture.whenStable();
    const router = TestBed.inject(Router);
    expect(router.parseUrl(router.url).queryParams['var-flags']).toBe('on-primary');
    expect(router.parseUrl(router.url).queryParams['q']).toBe('select');
    http.expectOne(req => req.params.get('flags') === 'ON_PRIMARY').flush(REPORT);

    component.setBy('TABLE');
    await harness.fixture.whenStable();
    expect(router.parseUrl(router.url).queryParams['var-rows']).toBe('table');
    http.expectOne(req => req.params.get('by') === 'TABLE').flush(REPORT);

    component.toggleAll();
    await harness.fixture.whenStable();
    expect(router.parseUrl(router.url).queryParams['var-all']).toBe('1');
    http.expectOne(req => req.params.get('all') === 'true').flush(REPORT);
    http.verify();
  });

  it('says so when nothing on the cluster is flagged, rather than showing an empty table', async () => {
    const {harness, http} = await open('/queries?view=servers');
    http.expectOne(req => req.url.endsWith('topology/compare'))
      .flush({...REPORT, rows: [], total: 0, flagCounts: {}, unflagged: 4});
    harness.fixture.detectChanges();
    expect(harness.routeNativeElement!.querySelector('.list-note')?.textContent).toContain('nothing is flagged');
  });
});
