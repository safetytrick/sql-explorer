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
    ({connection: name, verdict, problem: null, upSeconds, unusedHere: 1, readHere: 1});

  const component = () => new UnusedIndexesComponent(
    {} as never, {} as never);

  const withReport = (connections: IndexConnection[]) => {
    const it = component();
    it.report = {environment: 'prod', connections, complete: true, unused: [], basis: ''} as UnusedIndexReport;
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
});
