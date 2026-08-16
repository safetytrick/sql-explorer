import { COMMON_TEST_PROVIDERS } from '../testing/common-test-providers';
import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { SqlViewComponent } from './sql-view.component';
import { SqlProfile } from '../api-types';

describe('SqlViewComponent', () => {
  let component: SqlViewComponent;
  let fixture: ComponentFixture<SqlViewComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
    imports: [SqlViewComponent],
      providers: [COMMON_TEST_PROVIDERS]
})
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(SqlViewComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  /**
   * The shape ~5% of the corpus is actually in: the analyzer that fills
   * selectColumns / groupByColumns / schemas never ran or threw, so the wire
   * sends null rather than []. Reading `.length` off those threw during change
   * detection, which aborted the update pass — leaving the failure banner's
   * own interpolation empty, so a failed query showed no reason at all.
   *
   * Modelled on the live `SELECT NOW` profile, digest 0cb5ad1b….
   */
  function profileWithNullColumnArrays(): SqlProfile {
    return {
      id: '0cb5ad1b',
      digest: '0cb5ad1b',
      normalizedDigest: '0cb5ad1b',
      query: 'SELECT\n        NOW',
      type: 'SELECT',
      schemaName: 'ped',
      tables: [],
      criteriaColumns: [],
      joinColumns: [],
      selectColumns: null,
      groupByColumns: null,
      schemas: null,
      mismatchedJoins: [],
      generatedSource: null,
      truncated: false,
      analysisFailed: true,
      analysisSkipped: false,
      analysisFailureReason: 'Failure on: SelectColumnAnalyzer message: missing schema',
      isDml: false,
      isMultiSchema: false,
      runtimeMetrics: null,
      lineNumber: null,
      sourceFileName: null,
      sourcedFiles: [],
      queryActivityStatus: 'ACTIVE',
      modifiedForAnalysis: false,
      repeatedBlocks: null,
      mergedChildren: null,
      perfDigests: null
    } as SqlProfile;
  }

  it('renders a profile whose column arrays are null instead of crashing', () => {
    component.query = profileWithNullColumnArrays();
    component.loading = false;

    expect(() => fixture.detectChanges()).not.toThrow();
  });

  it('shows the analysis failure reason for such a profile', () => {
    component.query = profileWithNullColumnArrays();
    component.loading = false;
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Failure on: SelectColumnAnalyzer message: missing schema');
  });

  it('treats null column arrays as empty when collecting constraining columns', () => {
    component.query = profileWithNullColumnArrays();
    component.loading = false;

    // computeIndexPanels() spreads the same arrays; it must not throw either
    expect(() => fixture.detectChanges()).not.toThrow();
    expect(component.indexPanels).toEqual([]);
  });
});
