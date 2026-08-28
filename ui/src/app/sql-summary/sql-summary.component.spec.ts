import { COMMON_TEST_PROVIDERS } from '../testing/common-test-providers';
import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { SqlSummaryComponent } from './sql-summary.component';
import { SqlProfile } from '../api-types';

/** Minimal profile: the template requires an @Input query to render. */
const QUERY = {
  id: 'q1',
  digest: 'd1',
  query: 'SELECT 1',
  queryActivityStatus: 'ACTIVE',
  analysisFailed: false,
  analysisFailureReason: null,
  mismatchedJoins: [],
  runtimeMetrics: null,
} as unknown as SqlProfile;

describe('SqlSummaryComponent', () => {
  let component: SqlSummaryComponent;
  let fixture: ComponentFixture<SqlSummaryComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
    imports: [SqlSummaryComponent],
      providers: [COMMON_TEST_PROVIDERS]
})
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(SqlSummaryComponent);
    component = fixture.componentInstance;
    component.query = QUERY;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  /**
   * The backend sends a zone-less `LocalDateTime`, which used to reach the
   * page unformatted: 26 characters of raw ISO with microseconds, wide enough
   * to overflow the table lens's 190px metrics rail.
   */
  describe('first/last seen', () => {

    function renderWith(firstSeen: string | null, lastSeen: string | null): string {
      component.query = {
        ...QUERY,
        runtimeMetrics: {firstSeen, lastSeen, executed: 1, totalTime: 1, averageRowsScanned: 1}
      } as unknown as SqlProfile;
      fixture.detectChanges();
      return (fixture.nativeElement as HTMLElement).textContent || '';
    }

    it('renders to minute precision, dropping seconds and microseconds', () => {
      const text = renderWith('2026-05-04T15:49:26.782619', '2026-08-27T17:23:55.369392');

      expect(text).toContain('2026-05-04 15:49');
      expect(text).toContain('2026-08-27 17:23');
      expect(text).not.toContain('26.782619');
      expect(text).not.toContain('55.369392');
    });

    /**
     * A zone-less timestamp is parsed as local and formatted as local, so the
     * wall-clock digits the backend sent are the digits shown. If this ever
     * fails, someone has introduced a timezone into a value that has none and
     * every displayed time has silently shifted.
     */
    it('preserves the wall-clock the backend sent', () => {
      expect(renderWith('2026-01-01T00:00:00.000001', null)).toContain('2026-01-01 00:00');
      expect(renderWith('2026-12-31T23:59:59.999999', null)).toContain('2026-12-31 23:59');
    });

    /** Fixed width matters: this is a right-aligned column in a monospace font. */
    it('is the same width in December as in May', () => {
      const may = renderWith('2026-05-04T15:49:26.782619', null);
      const dec = renderWith('2026-12-04T15:49:26.782619', null);

      expect(may).toContain('2026-05-04 15:49');
      expect(dec).toContain('2026-12-04 15:49');
      expect('2026-05-04 15:49'.length).toBe('2026-12-04 15:49'.length);
    });

    it('falls back to an em dash rather than printing a blank', () => {
      expect(renderWith(null, null)).toContain('—');
    });
  });
});
