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

  /**
   * The backend sends a zone-less LocalDateTime, which reaches a template as
   * 26 characters of raw ISO with microseconds. Every other surface formats
   * it; this page did not, because the format was a private field on
   * SqlSummaryComponent rather than something shared.
   */
  describe('first and last seen', () => {

    function withSeen(): SqlProfile {
      const profile = profileWithNullColumnArrays();
      return {
        ...profile,
        runtimeMetrics: {
          firstSeen: '2026-05-04T15:49:26.782619',
          lastSeen: '2026-08-29T07:03:11.004821'
        }
      } as SqlProfile;
    }

    function rendered(): string {
      return (fixture.nativeElement as HTMLElement).textContent || '';
    }

    beforeEach(() => {
      component.query = withSeen();
      component.loading = false;
      fixture.detectChanges();
    });

    it('writes them to the minute, not to the microsecond', () => {
      expect(rendered()).toContain('2026-05-04 15:49');
      expect(rendered()).toContain('2026-08-29 07:03');
    });

    /** The whole defect: the raw value must not survive to the page. */
    it('never shows the raw ISO value', () => {
      expect(rendered()).not.toContain('782619');
      expect(rendered()).not.toContain('15:49:26');
      expect(rendered()).not.toContain('T15:49');
    });

    /** Fixed width is the point — 16 characters in May and in December alike. */
    it('uses the one shared format', () => {
      expect(component.SEEN_FORMAT).toBe('yyyy-MM-dd HH:mm');
    });
  });
});
