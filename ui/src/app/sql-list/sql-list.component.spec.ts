import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { SqlListComponent } from './sql-list.component';

describe('SqlListComponent', () => {
  let component: SqlListComponent;
  let fixture: ComponentFixture<SqlListComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
    imports: [SqlListComponent]
})
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(SqlListComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
