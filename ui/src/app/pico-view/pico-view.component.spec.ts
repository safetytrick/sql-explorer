import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { PicoViewComponent } from './pico-view.component';

describe('PicoViewComponent', () => {
  let component: PicoViewComponent;
  let fixture: ComponentFixture<PicoViewComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
      declarations: [ PicoViewComponent ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(PicoViewComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
