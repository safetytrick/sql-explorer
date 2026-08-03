import {ComponentFixture, TestBed, waitForAsync} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {provideHttpClientTesting} from '@angular/common/http/testing';
import {provideRouter} from '@angular/router';

import {ExplainViewComponent} from './explain-view.component';

describe('ExplainViewComponent', () => {
  let component: ExplainViewComponent;
  let fixture: ComponentFixture<ExplainViewComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
      imports: [ExplainViewComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(ExplainViewComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('annotates full scans and keyless reads', () => {
    component.onTextChange([
      'id\tselect_type\ttable\ttype\tpossible_keys\tkey\tkey_len\tref\trows\tfiltered\tExtra',
      '1\tSIMPLE\tmember_session\tALL\tNULL\tNULL\tNULL\tNULL\t1000\t10.00\tUsing where'
    ].join('\n'));

    expect(component.parsed.rows.length).toBe(1);
    const row = component.parsed.rows[0];
    expect(component.fullScan(row)).toBeTrue();
    expect(component.keyless(row)).toBeTrue();
    expect(component.estimatedRows(row)).toBe(100);
  });
});
