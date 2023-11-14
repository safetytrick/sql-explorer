import { async, ComponentFixture, TestBed } from '@angular/core/testing';

import { TaggedSqlViewComponent } from './tagged-sql-view.component';

describe('TaggedSqlViewComponent', () => {
  let component: TaggedSqlViewComponent;
  let fixture: ComponentFixture<TaggedSqlViewComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [ TaggedSqlViewComponent ]
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
