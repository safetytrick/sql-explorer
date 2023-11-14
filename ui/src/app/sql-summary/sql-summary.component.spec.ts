import { async, ComponentFixture, TestBed } from '@angular/core/testing';

import { SqlSummaryComponent } from './sql-summary.component';

describe('SqlSummaryComponent', () => {
  let component: SqlSummaryComponent;
  let fixture: ComponentFixture<SqlSummaryComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [ SqlSummaryComponent ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(SqlSummaryComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
