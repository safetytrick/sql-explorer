import { async, ComponentFixture, TestBed } from '@angular/core/testing';

import { SmallTableViewComponent } from './small-table-view.component';

describe('SmallTableViewComponent', () => {
  let component: SmallTableViewComponent;
  let fixture: ComponentFixture<SmallTableViewComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [ SmallTableViewComponent ]
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
