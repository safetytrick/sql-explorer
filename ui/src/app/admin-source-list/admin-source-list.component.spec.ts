import { async, ComponentFixture, TestBed } from '@angular/core/testing';

import { AdminSourceListComponent } from './admin-source-list.component';

describe('AdminSourceListComponent', () => {
  let component: AdminSourceListComponent;
  let fixture: ComponentFixture<AdminSourceListComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [ AdminSourceListComponent ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(AdminSourceListComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
