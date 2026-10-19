import {findings, groupByCluster, sharedWindow, showsClusterHeading, worstVerdict} from './coverage.service';
import type {ClusterSchemaStatus, CoverageFinding, CoverageReport, SourceCoverage} from './api-types';

/**
 * The two places a coverage number can be quietly turned into a lie: folding
 * several sources into one window, and folding several verdicts into one
 * chip (sqlx-n01).
 */
describe('coverage helpers', () => {

  const finding = (signal: CoverageFinding['signal'],
                   verdict: CoverageFinding['verdict']): CoverageFinding =>
    ({signal, verdict, measurement: 'm', basis: 'b', explanation: 'e'});

  const source = (from: string | null, to = '2026-08-30T00:00:00',
                  verdict: CoverageFinding['verdict'] = 'OK'): SourceCoverage => ({
    sourceRef: 'primary', origin: 'PERFORMANCE_SCHEMA', capturedAt: null, profiles: 1,
    window: from === null ? null : {from, to, days: 118},
    findings: [finding('DIGEST_CAPACITY', verdict)]
  });

  const report = (...sources: SourceCoverage[]): CoverageReport =>
    ({environment: 'env', loaded: true, loadedOn: null, originTable: 'sys.x$statement_analysis', sources,
      clusters: []});

  describe('sharedWindow', () => {

    it('reports the window when every source agrees', () => {
      const window = sharedWindow(report(source('2026-05-04T00:00:00'), source('2026-05-04T00:00:00')));
      expect(window?.days).toBe(118);
    });

    // "across 118 days" is only true if every source says so. Picking the
    // widest, or the first, would state a period nothing actually observed.
    it('reports nothing when two sources observed different periods', () => {
      expect(sharedWindow(report(source('2026-05-04T00:00:00'), source('2026-08-01T00:00:00')))).toBeNull();
    });

    // One source that never recorded its timestamps makes the environment-wide
    // claim unsupportable, however confident the others are.
    it('reports nothing when any source has no window', () => {
      expect(sharedWindow(report(source('2026-05-04T00:00:00'), source(null)))).toBeNull();
    });

    it('reports nothing when there are no sources', () => {
      expect(sharedWindow(report())).toBeNull();
      expect(sharedWindow(null)).toBeNull();
    });
  });

  describe('worstVerdict', () => {

    it('is fine only when every source is fine', () => {
      expect(worstVerdict(findings(report(source('2026-05-04T00:00:00'), source('2026-05-04T00:00:00')),
        'DIGEST_CAPACITY'))).toBe('OK');
    });

    // One source missing statements makes the corpus incomplete no matter how
    // healthy the others are.
    it('takes the worst, not the common case', () => {
      expect(worstVerdict(findings(
        report(source('2026-05-04T00:00:00'), source('2026-05-04T00:00:00', '2026-08-30T00:00:00', 'IMPAIRED')),
        'DIGEST_CAPACITY'))).toBe('IMPAIRED');
    });

    // Unknown is not a pass: it outranks OK so the page cannot claim a clean
    // bill of health it has no evidence for.
    it('prefers unknown over fine', () => {
      expect(worstVerdict(findings(
        report(source('2026-05-04T00:00:00'), source('2026-05-04T00:00:00', '2026-08-30T00:00:00', 'UNKNOWN')),
        'DIGEST_CAPACITY'))).toBe('UNKNOWN');
    });

    // ...but a known problem outranks an unknown, because it is actionable.
    it('prefers impaired over unknown', () => {
      expect(worstVerdict([finding('DIGEST_CAPACITY', 'UNKNOWN'), finding('DIGEST_CAPACITY', 'IMPAIRED')]))
        .toBe('IMPAIRED');
    });

    it('says nothing when there is nothing to judge', () => {
      expect(worstVerdict([])).toBeNull();
    });
  });

  describe('findings', () => {

    it('returns one entry per source, skipping sources that lack the signal', () => {
      const partial: SourceCoverage = {...source('2026-05-04T00:00:00'), findings: []};
      expect(findings(report(source('2026-05-04T00:00:00'), partial), 'DIGEST_CAPACITY').length).toBe(1);
    });
  });

  describe('groupByCluster (sqlx-sqy.2)', () => {
    const named = (sourceRef: string): SourceCoverage => ({...source(null), sourceRef});
    const cluster = (name: string, servers: string[], extra: Partial<ClusterSchemaStatus> = {}): ClusterSchemaStatus =>
      ({cluster: name, servers, schemaServer: servers[0], fallback: false, passedOver: [], failure: null, ...extra});

    it('puts each source under the cluster holding its server, in cluster order, with the rest last', () => {
      const groups = groupByCluster({
        ...report(named('upload.csv'), named('shard2'), named('shard1-replica-a'), named('shard1')),
        clusters: [cluster('shard1', ['shard1', 'shard1-replica-a']), cluster('shard2', ['shard2'])]
      });

      expect(groups.map(group => [group.cluster?.cluster ?? null, group.sources.map(s => s.sourceRef)])).toEqual([
        ['shard1', ['shard1-replica-a', 'shard1']],
        ['shard2', ['shard2']],
        [null, ['upload.csv']]
      ]);
    });

    it('heads a cluster of several servers, or one whose schema was not its first choice', () => {
      expect(showsClusterHeading(cluster('shard1', ['shard1', 'shard1-replica-a']))).toBeTrue();
      expect(showsClusterHeading(cluster('regression', ['regression']))).toBeFalse();
      expect(showsClusterHeading(cluster('global', ['global'], {failure: 'Access denied'}))).toBeTrue();
      expect(showsClusterHeading(null)).toBeFalse();
    });
  });
});

import {TestBed} from '@angular/core/testing';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {provideHttpClient} from '@angular/common/http';
import {CoverageService} from './coverage.service';
import {EnvironmentService} from './environment.service';

/**
 * The cache is what makes coverage cheap enough to ask for on any page, and
 * also the one way it could actively misinform: /coverage is resolved from the
 * environment header, and switching environments does not reload the page.
 */
describe('CoverageService caching', () => {

  let service: CoverageService;
  let environments: EnvironmentService;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.removeItem('sql-explorer.environment');
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), CoverageService, EnvironmentService]
    });
    service = TestBed.inject(CoverageService);
    environments = TestBed.inject(EnvironmentService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const body = {
    environment: 'env', loaded: true, loadedOn: null,
    originTable: 'sys.x$statement_analysis', sources: [], clusters: []
  };

  it('asks once for several readers on the same page', () => {
    service.getCoverage().subscribe();
    service.getCoverage().subscribe();

    http.expectOne('/api/sql-explorer/coverage').flush(body);
  });

  it('asks again after the environment changes', () => {
    service.getCoverage().subscribe();
    http.expectOne('/api/sql-explorer/coverage').flush(body);

    environments.setCurrent('other');
    service.getCoverage().subscribe();

    http.expectOne('/api/sql-explorer/coverage').flush(body);
  });
});
