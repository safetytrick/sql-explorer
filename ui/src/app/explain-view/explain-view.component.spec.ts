import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { ExplainViewComponent } from './explain-view.component';

describe('ExplainViewComponent', () => {
  let component: ExplainViewComponent;
  let fixture: ComponentFixture<ExplainViewComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
    imports: [ExplainViewComponent]
})
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(ExplainViewComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
