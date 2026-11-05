import { COMMON_TEST_PROVIDERS } from '../testing/common-test-providers';
import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { ColumnLinkComponent } from './column-link.component';

describe('ColumnLinkComponent', () => {
  let component: ColumnLinkComponent;
  let fixture: ComponentFixture<ColumnLinkComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
    imports: [ColumnLinkComponent],
      providers: [COMMON_TEST_PROVIDERS]
})
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(ColumnLinkComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  function href(): string {
    return fixture.nativeElement.querySelector('a').getAttribute('href');
  }

  /**
   * The canonical drill-down is the table lens with `?columns=`. The old
   * `/table/{s}/{t}/{c}` path still redirects to it, but the href a user copies
   * out of the page should already be the shareable one.
   */
  it('links to the table lens with the column drilled into', () => {
    component.schema = 'app_main';
    component.columnReference = 'app_main.user_profile.user_id';
    fixture.detectChanges();

    expect(href()).toBe('/table/app_main/user_profile?columns=app_main.user_profile.user_id');
    expect(fixture.nativeElement.textContent.trim()).toBe('user_profile.user_id');
  });

  /** An unqualified reference keeps the schema supplied separately. */
  it('qualifies the filter with the separately supplied schema', () => {
    component.schema = 'app_main';
    component.columnReference = 'user_profile.user_id';
    fixture.detectChanges();

    expect(href()).toBe('/table/app_main/user_profile?columns=app_main.user_profile.user_id');
  });

  /**
   * With no schema there is nothing to build a `schema.table.column` filter
   * from, and an unqualified one matches nothing — so the link drops the filter
   * rather than opening a query list that looks empty for the wrong reason.
   */
  it('omits the filter when no schema is known', () => {
    component.schema = '';
    component.columnReference = 'user_profile.user_id';
    fixture.detectChanges();

    expect(href()).not.toContain('columns=');
  });
});
