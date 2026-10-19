import {Title} from '@angular/platform-browser';
import {CoverageLensComponent} from './coverage-lens.component';
import {CoverageService} from '../coverage.service';
import {EnvironmentService} from '../environment.service';
import type {CoverageSignal} from '../api-types';

describe('CoverageLensComponent', () => {
  let component: CoverageLensComponent;

  // label() reads nothing the constructor is given, so nothing is wired up
  beforeEach(() => {
    component = new CoverageLensComponent(
      jasmine.createSpyObj<CoverageService>('CoverageService', ['getCoverage']),
      {} as EnvironmentService,
      jasmine.createSpyObj<Title>('Title', ['setTitle']));
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
});
