import { async, ComponentFixture, TestBed } from '@angular/core/testing';

import { ExplainViewComponent } from './explain-view.component';

describe('ExplainViewComponent', () => {
  let component: ExplainViewComponent;
  let fixture: ComponentFixture<ExplainViewComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [ ExplainViewComponent ]
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
