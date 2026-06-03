import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { SqlViewComponent } from './sql-view.component';

describe('SqlViewComponent', () => {
  let component: SqlViewComponent;
  let fixture: ComponentFixture<SqlViewComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
    imports: [SqlViewComponent]
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
});
