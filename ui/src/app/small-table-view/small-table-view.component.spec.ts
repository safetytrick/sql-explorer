import { COMMON_TEST_PROVIDERS } from '../testing/common-test-providers';
import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { SmallTableViewComponent } from './small-table-view.component';
import { TableStructure } from '../api-types';

/** Minimal structure: the template requires an @Input table to render. */
const TABLE = {
  schema: 'tdb',
  name: 'orders',
  type: 'TABLE',
  remarks: '',
  foreignKeys: [],
  indices: [],
} as unknown as TableStructure;

describe('SmallTableViewComponent', () => {
  let component: SmallTableViewComponent;
  let fixture: ComponentFixture<SmallTableViewComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
    imports: [SmallTableViewComponent],
      providers: [COMMON_TEST_PROVIDERS]
})
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(SmallTableViewComponent);
    component = fixture.componentInstance;
    component.table = TABLE;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
