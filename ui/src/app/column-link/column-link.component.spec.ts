import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { ColumnLinkComponent } from './column-link.component';

describe('ColumnLinkComponent', () => {
  let component: ColumnLinkComponent;
  let fixture: ComponentFixture<ColumnLinkComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
      declarations: [ ColumnLinkComponent ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(ColumnLinkComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
