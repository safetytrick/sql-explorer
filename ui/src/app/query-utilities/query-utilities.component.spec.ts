import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { QueryUtilitiesComponent } from './query-utilities.component';

describe('QueryUtilitiesComponent', () => {
  let component: QueryUtilitiesComponent;
  let fixture: ComponentFixture<QueryUtilitiesComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
      declarations: [ QueryUtilitiesComponent ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(QueryUtilitiesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
