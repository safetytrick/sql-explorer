import {HttpTestingController} from '@angular/common/http/testing';
import { COMMON_TEST_PROVIDERS } from '../testing/common-test-providers';
import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { QueryUtilitiesComponent } from './query-utilities.component';

describe('QueryUtilitiesComponent', () => {
  let component: QueryUtilitiesComponent;
  let fixture: ComponentFixture<QueryUtilitiesComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
    imports: [QueryUtilitiesComponent],
      providers: [COMMON_TEST_PROVIDERS]
})
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(QueryUtilitiesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // the backend prefixes a marker when it cannot parse; the page says so instead of showing it (sqlx-2ko.1)
  it('shows a normalized result, and reports a parse error rather than printing its marker', () => {
    const http = TestBed.inject(HttpTestingController);
    component.inputControl.setValue('SELEKT 1');
    component.doNormalize();
    http.expectOne(r => r.url.endsWith('util/normalize')).flush({text: '/* parse error */\nSELEKT 1', repeats: []});

    expect(component.parseError).toBeTrue();
    expect(component.result).toBe('SELEKT 1');
    expect(component.resultLabel).toBe('Normalized');
  });

  it('says the request failed rather than leaving an old result up', () => {
    const http = TestBed.inject(HttpTestingController);
    component.inputControl.setValue('SELECT 1');
    component.doClean();
    http.expectOne(r => r.url.endsWith('util/clean')).flush('no', {status: 500, statusText: 'err'});

    expect(component.failed).toBeTrue();
    expect(component.result).toBeNull();
  });
});
