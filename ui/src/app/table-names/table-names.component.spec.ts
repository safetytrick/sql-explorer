import { async, ComponentFixture, TestBed } from '@angular/core/testing';

import { TableNamesComponent } from './table-names.component';

describe('TableNamesComponent', () => {
  let component: TableNamesComponent;
  let fixture: ComponentFixture<TableNamesComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [ TableNamesComponent ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(TableNamesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
