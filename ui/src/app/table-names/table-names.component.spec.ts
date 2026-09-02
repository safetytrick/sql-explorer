import { COMMON_TEST_PROVIDERS } from '../testing/common-test-providers';
import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { TableNamesComponent } from './table-names.component';
import { emptyTagFilter } from '../tag-filter/tag-filter-url';
import {tableTarget} from '../tag-path';

describe('TableNamesComponent', () => {
  let component: TableNamesComponent;
  let fixture: ComponentFixture<TableNamesComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
    imports: [TableNamesComponent],
      providers: [COMMON_TEST_PROVIDERS]
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

  /**
   * The bulk-tag set is what is ON SCREEN, and on this page that is narrower
   * than the tag filter leaves: the all/tables/views control narrows again,
   * and it does so inside the list component. Tagging what `visible` holds
   * while the user looks at "views only" would tag base tables they cannot
   * see — invisible, and plausible enough to go unnoticed (sqlx-agl).
   */
  describe('the set a bulk tag would act on', () => {

    const table = (schema: string, name: string, view: boolean) =>
      ({schema, name, view, tags: []}) as never;

    beforeEach(() => {
      component.tables = [
        table('app', 'users', false),
        table('app', 'user_summary', true),
        table('app', 'orders', false)
      ];
    });

    it('is every row when nothing is filtered', () => {
      component.typeFilter = 'all';
      component.onFilter(emptyTagFilter());

      expect(component.bulkTargets).toEqual([
        tableTarget('app', 'users'),
        tableTarget('app', 'user_summary'),
        tableTarget('app', 'orders')
      ]);
      expect(component.bulkLabel).toBe('all 3 tables');
    });

    it('follows the views filter', () => {
      component.typeFilter = 'views';
      component.onFilter(emptyTagFilter());

      expect(component.bulkTargets).toEqual([tableTarget('app', 'user_summary')]);
      expect(component.bulkLabel).toBe('1 of 3 tables');
    });

    it('follows the tables filter', () => {
      component.typeFilter = 'tables';
      component.onFilter(emptyTagFilter());

      expect(component.bulkTargets).toEqual([
        tableTarget('app', 'users'),
        tableTarget('app', 'orders')
      ]);
      expect(component.bulkLabel).toBe('2 of 3 tables');
    });

    // Before anything loads there is nothing to tag, and the bar has to be
    // disabled rather than offering to act on an empty set.
    it('is empty before the page has loaded', () => {
      component.tables = [];
      component.onFilter(emptyTagFilter());

      expect(component.bulkTargets).toEqual([]);
      expect(component.displayed.length).toBe(0);
    });
  });
});
