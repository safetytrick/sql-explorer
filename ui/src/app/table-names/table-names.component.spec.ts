import { COMMON_TEST_PROVIDERS } from '../testing/common-test-providers';
import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { TableNamesComponent } from './table-names.component';
import { emptyTagFilter } from '../tag-filter/tag-filter-url';
import {tableTarget} from '../tag-path';
import {provideRouter, Router} from '@angular/router';
import {RouterTestingHarness} from '@angular/router/testing';
import {HttpTestingController} from '@angular/common/http/testing';

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

    it('follows the name search, wildcard included', () => {
      component.search = {name: 'user*', columns: [], exclude: []};
      component.onFilter(emptyTagFilter());

      expect(component.displayed.map(t => t.name)).toEqual(['users', 'user_summary']);
      expect(component.bulkLabel).toBe('2 of 3 tables');
    });

    // the one or two rows a bulk tag should pass over stay on screen, unchecked
    it('passes over unchecked rows of a filtered list', () => {
      component.excluded = new Set(['app.orders']);
      component.typeFilter = 'tables';
      component.onFilter(emptyTagFilter());

      expect(component.filtered).toBeTrue();
      expect(component.bulkTargets).toEqual([tableTarget('app', 'users')]);
      expect(component.bulkLabel).toBe('1 of 3 tables');
    });

    // with no filter the checkboxes are hidden, so an exclusion left in the
    // URL must not quietly drop a row from the bulk tag
    it('shows no checkboxes and skips nothing when nothing is filtered', () => {
      component.typeFilter = 'all';
      component.excluded = new Set(['app.orders']);
      component.onFilter(emptyTagFilter());

      expect(component.filtered).toBeFalse();
      expect(component.bulkTargets.length).toBe(3);
      expect(component.bulkLabel).toBe('all 3 tables');
    });

    it('passes over unchecked rows', () => {
      component.search = {name: 'a', columns: [], exclude: []};
      component.excluded = new Set(['app.orders']);
      component.onFilter(emptyTagFilter());

      expect(component.displayed.length).toBe(3);
      expect(component.bulkTargets).toEqual([tableTarget('app', 'users'), tableTarget('app', 'user_summary')]);
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

/** The search lives in the URL, and columns are answered by the server (sqlx-v86). */
describe('TableNamesComponent search', () => {

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        ...COMMON_TEST_PROVIDERS,
        provideRouter([{ path: 'tables', component: TableNamesComponent }])
      ]
    });
  });

  it('asks the server for tables having every column in a shared link', async () => {
    const harness = await RouterTestingHarness.create('/tables?var-columns=entity_id,entity_type&var-name=invoice_*');
    const component: TableNamesComponent = harness.routeDebugElement!.componentInstance;
    const http = TestBed.inject(HttpTestingController);

    const request = http.expectOne(r => r.url.endsWith('/tables'));
    expect(request.request.params.getAll('columns')).toEqual(['entity_id', 'entity_type']);
    expect(component.search.name).toBe('invoice_*');
  });

  it('starts a new search with every row checked', async () => {
    const harness = await RouterTestingHarness.create('/tables?var-exclude=app.notes');
    const component: TableNamesComponent = harness.routeDebugElement!.componentInstance;
    expect(component.excluded.has('app.notes')).toBeTrue();

    component.setColumns('entity_id, entity_type');
    await harness.fixture.whenStable();

    const url = TestBed.inject(Router).url;
    expect(url).toContain('var-columns=entity_id,entity_type');
    expect(url).not.toContain('var-exclude');
    expect(component.excluded.size).toBe(0);
  });
});
