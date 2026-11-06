import {of} from 'rxjs';
import {UnusedIndexesComponent} from './unused-indexes.component';
import type {IndexConnection, UnusedIndexReport} from './unused-indexes.service';

/**
 * The page whose finding is acted on by deleting something, so the parts that
 * qualify the finding matter as much as the list (sqlx-vjr).
 */
describe('UnusedIndexesComponent', () => {

  const connection = (name: string, verdict: IndexConnection['verdict'],
                      upSeconds: number | null): IndexConnection =>
    ({connection: name, verdict, problem: null, note: null, upSeconds, unusedHere: 1, readHere: 1});

  const component = () => new UnusedIndexesComponent(
    {} as never, {} as never);

  const withReport = (connections: IndexConnection[]) => {
    const it = component();
    it.report = {environment: 'prod', connections, complete: true, unused: [], basis: '', readAt: '', readMillis: 0,
      minimumDays: 7} as UnusedIndexReport;
    return it;
  };

  /** Rendered as "≤N days", because uptime bounds the window rather than being it. */
  it('reports uptime in whole days', () => {
    expect(component().daysUp(connection('primary', 'OK', 637816))).toBe(7);
  });

  it('reports no window for a connection that did not answer', () => {
    expect(component().daysUp(connection('replica', 'UNKNOWN', null))).toBeNull();
  });

  /**
   * The header says how many connections were actually read. "8 indexes" over
   * one of three connections is a very different claim from over three of
   * three, and the count is what tells them apart.
   */
  it('counts only the connections that answered', () => {
    const page = withReport([
      connection('primary', 'OK', 999999),
      connection('replica', 'IMPAIRED', 3600),
      connection('analytics', 'UNKNOWN', null)
    ]);

    expect(page.connectionsRead).toBe(2);
  });

  it('counts none when nothing answered', () => {
    expect(withReport([connection('replica', 'UNKNOWN', null)]).connectionsRead).toBe(0);
  });

  // a glance at what the capture covers, from the coverage report (sqlx-1bo.4)
  it('summarises the captured window and digest capacity', () => {
    const window = {from: '2026-10-01T00:00:00', to: '2026-11-01T00:00:00', days: 31};
    const coverage = {getCoverage: () => of({sources: [
      {window, findings: [{signal: 'DIGEST_CAPACITY', verdict: 'IMPAIRED'}]}
    ]})};
    const it = new UnusedIndexesComponent({} as never, {} as never, coverage as never);
    (it as unknown as {loadCoverage(): void}).loadCoverage();

    expect(it.window).toEqual(window as never);
    expect(it.capacity).toBe('IMPAIRED');
  });

  // reading connects to every server, so it waits to be asked (sqlx-1bo.2)
  describe('reading when asked', () => {
    const report = {environment: 'prod', connections: [], complete: true, unused: [], basis: '',
      readAt: '2026-11-05T12:00:00Z', readMillis: 4200, minimumDays: 7} as UnusedIndexReport;

    it('shows the last report without reading, and reads only when asked', () => {
      const service = {getLatest: jasmine.createSpy().and.returnValue(of(report)),
                       read: jasmine.createSpy().and.returnValue(of({...report, readAt: '2026-11-06T09:00:00Z'}))};
      const page = new UnusedIndexesComponent(service as never, {} as never);
      (page as unknown as {load(): void}).load();
      expect(page.report).toBe(report);
      expect(service.read).not.toHaveBeenCalled();

      page.readNow();
      expect(service.read).toHaveBeenCalledTimes(1);
      expect(page.report!.readAt).toBe('2026-11-06T09:00:00Z');
      expect(page.reading).toBeFalse();
    });

    it('describes the kept report: how old, and what reading took', () => {
      const page = component();
      page.report = {...report, readAt: new Date(Date.now() - 3 * 3600 * 1000).toISOString()};
      expect(page.age).toBe('3h');
      expect(page.took).toBe('4.2 s');
      page.report = {...report, readMillis: 850};
      expect(page.took).toBe('850 ms');
    });

    it('has no report, rather than an empty one, when nothing has been read', () => {
      const service = {getLatest: () => of(null), read: jasmine.createSpy()};
      const page = new UnusedIndexesComponent(service as never, {} as never);
      (page as unknown as {load(): void}).load();
      expect(page.report).toBeNull();
      expect(page.failed).toBeFalse();
      expect(service.read).not.toHaveBeenCalled();
    });
  });
});
