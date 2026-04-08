import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { TableLinkComponent } from './table-link.component';

describe('TableLinkComponent', () => {
  let component: TableLinkComponent;
  let fixture: ComponentFixture<TableLinkComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
      declarations: [ TableLinkComponent ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(TableLinkComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
