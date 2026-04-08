import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { TaggedTableViewComponent } from './tagged-table-view.component';

describe('TaggedTableViewComponent', () => {
  let component: TaggedTableViewComponent;
  let fixture: ComponentFixture<TaggedTableViewComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
      declarations: [ TaggedTableViewComponent ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(TaggedTableViewComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
