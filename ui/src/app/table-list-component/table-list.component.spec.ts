import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { COMMON_TEST_PROVIDERS } from '../testing/common-test-providers';
import { TableListComponent } from './table-list.component';

describe('TableListComponentComponent', () => {
  let component: TableListComponent;
  let fixture: ComponentFixture<TableListComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
    imports: [TableListComponent],
    providers: [COMMON_TEST_PROVIDERS]
})
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(TableListComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // one schema's list names the schema in its header (sqlx-c8w.4)
  it('draws the Schema column unless told the list is one schema', () => {
    component.tables = [{schema: 'app', name: 'orders', view: false, tags: [], numberOfQueries: 1, totalQueryTime: 1}];
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('.schema-col').length).toBe(2);

    fixture.componentRef.setInput('showSchema', false);
    fixture.detectChanges();
    expect(el.querySelectorAll('.schema-col').length).toBe(0);
  });

  it('explains what # of Queries and Total Query Time count, on hover', () => {
    const terms = (fixture.nativeElement as HTMLElement).querySelectorAll('th .term');
    const titles = Array.from(terms).map(term => term.getAttribute('title') ?? '');
    expect(titles.length).toBe(4);
    expect(titles[0]).toContain('Distinct query patterns');
    expect(titles[1]).toContain('Time spent executing');
    // sqlx-n5j.2: the row figure says it is an estimate
    expect(titles[2]).toContain('an estimate');
    expect(titles[3]).toContain('DATA_LENGTH + INDEX_LENGTH');
  });

  it('shows the row estimate as one, and the size, and nothing when unknown', () => {
    component.tables = [
      {schema: 'app', name: 'orders', view: false, tags: [], numberOfQueries: 1, totalQueryTime: 1,
        rowsEstimate: 1583066, sizeBytes: 163840},
      {schema: 'app', name: 'events', view: false, tags: [], numberOfQueries: 1, totalQueryTime: 1}
    ];
    fixture.detectChanges();
    const cells = (selector: string) => Array.from((fixture.nativeElement as HTMLElement)
      .querySelectorAll('tbody ' + selector)).map(cell => cell.textContent?.trim());

    expect(cells('.rows-est')).toEqual(['≈1.6M', '']);
    expect(cells('.table-size')).toEqual(['160 KiB', '']);
  });

  // a long list draws in steps; selection still covers every row (sqlx-c8w.11)
  it('draws a long list 250 rows at a time, while select-all still covers every row', () => {
    component.tables = Array.from({length: 600}, (_, at) =>
      ({schema: 'app', name: 't' + at, view: false, tags: [], numberOfQueries: 1, totalQueryTime: 1}));
    component.selectable = true;
    fixture.detectChanges();
    const rows = () => (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr').length;
    expect(rows()).toBe(250);

    component.renderMore();
    fixture.detectChanges();
    expect(rows()).toBe(500);

    const emitted: Set<string>[] = [];
    component.excludedChange.subscribe(set => emitted.push(set));
    component.toggleAll();
    expect(emitted[0].size).toBe(600);
  });

  /** The checkboxes report exceptions; the page that owns them decides what they mean (sqlx-v86). */
  describe('when selectable', () => {
    const table = (name: string) => ({schema: 'app', name, view: false, tags: [], numberOfQueries: 1, totalQueryTime: 1});
    let emitted: Set<string>[];

    beforeEach(() => {
      component.selectable = true;
      component.tables = [table('notes'), table('attachments'), table('orders')];
      emitted = [];
      component.excludedChange.subscribe(set => emitted.push(set));
    });

    it('starts every row checked, and unchecks one', () => {
      expect(component.allChecked()).toBeTrue();

      component.toggleRow(component.tables[1]);

      expect([...emitted[0]]).toEqual(['app.attachments']);
    });

    it('shows a partial selection as indeterminate, and select-all checks every row', () => {
      component.excluded = new Set(['app.orders']);
      expect(component.allChecked()).toBeFalse();
      expect(component.someChecked()).toBeTrue();

      component.toggleAll();

      expect(emitted[0].size).toBe(0);
    });

    it('unchecks every row when all are checked', () => {
      component.toggleAll();

      expect([...emitted[0]].sort()).toEqual(['app.attachments', 'app.notes', 'app.orders']);
    });

    it('draws the checkboxes only when asked', () => {
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelectorAll('input[type=checkbox]').length).toBe(4);

      component.selectable = false;
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelectorAll('input[type=checkbox]').length).toBe(0);
    });
  });
});
