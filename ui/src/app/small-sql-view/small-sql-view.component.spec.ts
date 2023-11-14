import { async, ComponentFixture, TestBed } from '@angular/core/testing';

import { SmallSqlViewComponent } from './small-sql-view.component';

describe('SmallSqlViewComponent', () => {
  let component: SmallSqlViewComponent;
  let fixture: ComponentFixture<SmallSqlViewComponent>;

  beforeEach(async(() => {
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
