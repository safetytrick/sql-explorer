import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import {SchemaListComponent} from './schema-list.component';

describe('SchemaListComponent', () => {
  let component: SchemaListComponent;
  let fixture: ComponentFixture<SchemaListComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
    imports: [SchemaListComponent]
})
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(SchemaListComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
