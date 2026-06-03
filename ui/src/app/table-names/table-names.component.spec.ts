import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { TableNamesComponent } from './table-names.component';

describe('TableNamesComponent', () => {
  let component: TableNamesComponent;
  let fixture: ComponentFixture<TableNamesComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
    imports: [TableNamesComponent]
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
