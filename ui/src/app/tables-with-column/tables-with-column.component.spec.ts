import { async, ComponentFixture, TestBed } from '@angular/core/testing';

import { TablesWithColumnComponent } from './tables-with-column.component';

describe('TablesWithColumnComponent', () => {
  let component: TablesWithColumnComponent;
  let fixture: ComponentFixture<TablesWithColumnComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [ TablesWithColumnComponent ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(TablesWithColumnComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
