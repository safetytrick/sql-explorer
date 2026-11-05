import {CoverageLensComponent, summariseFindings} from './coverage-lens.component';
import {CoverageService} from '../coverage.service';
import {EnvironmentService} from '../environment.service';
import type {CoverageSignal} from '../api-types';

describe('CoverageLensComponent', () => {
  let component: CoverageLensComponent;

  // label() reads nothing the constructor is given, so nothing is wired up
  beforeEach(() => {
    component = new CoverageLensComponent(
      jasmine.createSpyObj<CoverageService>('CoverageService', ['getCoverage']),
      {} as EnvironmentService);
  });

  // ARCHIVED_HISTORY reached the server without a label here, and the
  // template's label(...).title threw for every loaded source (sqlx-jdf).
  it('labels every signal the server sends', () => {
    const signals: CoverageSignal[] = ['DIGEST_CAPACITY', 'DIGEST_TRUNCATION', 'OBSERVATION_WINDOW',
      'PROVENANCE', 'SAMPLING_GAPS', 'ARCHIVED_HISTORY', 'UNREACHABLE', 'PENDING_ANALYSIS'];
    for (const signal of signals) {
      expect(component.label(signal).title).withContext(signal).not.toEqual(signal);
    }
  });

  it('falls back to the raw name for a signal it does not know yet', () => {
    expect(component.label('SOMETHING_NEW' as CoverageSignal)).toEqual({title: 'SOMETHING_NEW', question: ''});
  });

  // each check once, worst first, naming the sources that are not fine (sqlx-om1.5)
  it('summarises findings across sources, one row per check', () => {
    const finding = (signal: string, verdict: string) => ({signal, verdict, measurement: '', basis: '', explanation: ''});
    const rows = summariseFindings({sources: [
      {sourceRef: 'primary', findings: [finding('DIGEST_CAPACITY', 'OK'), finding('RESET_IN_WINDOW', 'IMPAIRED')]},
      {sourceRef: 'replica', findings: [finding('DIGEST_CAPACITY', 'UNKNOWN'), finding('RESET_IN_WINDOW', 'OK')]}
    ]} as never);

    expect(rows.map(row => row.signal)).toEqual(['RESET_IN_WINDOW', 'DIGEST_CAPACITY'] as never);
    // explicit fields: jasmine.objectContaining throws in this build (see the workbench spec)
    expect([rows[0].impaired, rows[0].ok, rows[0].problems]).toEqual([1, 1, ['primary']]);
    expect([rows[1].unknown, rows[1].ok, rows[1].problems]).toEqual([1, 1, ['replica']]);
  });
});
