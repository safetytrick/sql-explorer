import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { SmallTableViewComponent } from './small-table-view.component';

describe('SmallTableViewComponent', () => {
  let component: SmallTableViewComponent;
  let fixture: ComponentFixture<SmallTableViewComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
    imports: [SmallTableViewComponent]
})
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(SmallTableViewComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
