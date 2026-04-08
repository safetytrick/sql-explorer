import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { TableGraphLoaderComponent } from './table-graph-loader.component';

describe('TableGraphLoaderComponent', () => {
  let component: TableGraphLoaderComponent;
  let fixture: ComponentFixture<TableGraphLoaderComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
      declarations: [ TableGraphLoaderComponent ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(TableGraphLoaderComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
