import { COMMON_TEST_PROVIDERS } from '../testing/common-test-providers';
import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { TablesWithColumnComponent } from './tables-with-column.component';

describe('TablesWithColumnComponent', () => {
  let component: TablesWithColumnComponent;
  let fixture: ComponentFixture<TablesWithColumnComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
    imports: [TablesWithColumnComponent],
      providers: [COMMON_TEST_PROVIDERS]
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
