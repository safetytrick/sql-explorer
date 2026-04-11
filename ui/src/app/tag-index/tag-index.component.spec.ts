import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { TagIndexComponent } from './tag-index.component';

describe('TagIndexComponent', () => {
  let component: TagIndexComponent;
  let fixture: ComponentFixture<TagIndexComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
    imports: [TagIndexComponent]
})
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(TagIndexComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
