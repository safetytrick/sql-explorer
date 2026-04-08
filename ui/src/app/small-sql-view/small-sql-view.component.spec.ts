import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { SmallSqlViewComponent } from './small-sql-view.component';

describe('SmallSqlViewComponent', () => {
  let component: SmallSqlViewComponent;
  let fixture: ComponentFixture<SmallSqlViewComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
      declarations: [ SmallSqlViewComponent ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(SmallSqlViewComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
