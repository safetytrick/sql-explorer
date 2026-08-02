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
});
