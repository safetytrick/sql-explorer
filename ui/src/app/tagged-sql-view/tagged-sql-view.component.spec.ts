import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { TaggedSqlViewComponent } from './tagged-sql-view.component';

describe('TaggedSqlViewComponent', () => {
  let component: TaggedSqlViewComponent;
  let fixture: ComponentFixture<TaggedSqlViewComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
    imports: [TaggedSqlViewComponent]
})
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(TaggedSqlViewComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
