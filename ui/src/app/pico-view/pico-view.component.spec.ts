import { async, ComponentFixture, TestBed } from '@angular/core/testing';

import { PicoViewComponent } from './pico-view.component';

describe('PicoViewComponent', () => {
  let component: PicoViewComponent;
  let fixture: ComponentFixture<PicoViewComponent>;

  beforeEach(async(() => {
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
