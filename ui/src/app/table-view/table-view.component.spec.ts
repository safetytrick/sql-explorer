import {ComponentFixture, TestBed, waitForAsync} from '@angular/core/testing';
import {ActivatedRoute, provideRouter} from '@angular/router';
import {of} from 'rxjs';

import {TableViewComponent} from './table-view.component';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {analyzedSqlServiceStub} from '../testing/analyzed-sql-service-stub';
import {Table} from '../api-types';

describe('TableViewComponent', () => {
  let fixture: ComponentFixture<TableViewComponent>;

  /** A payload whose own schema/name echo is empty — the page must not depend on it. */
  const tableWithoutSchema = {
    name: '', schema: '', numberOfQueries: 0, totalQueryTime: 0,
    queries: [], structure: null, foreignKeyTables: [], joinTables: [], relatedTables: []
  } as unknown as Table;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
      imports: [TableViewComponent],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { params: of({ schema: 'app_main', name: 'permissions' }) } },
        // full-surface stub: the template's children (tag view, …) call
        // service methods this spec doesn't assert on
        { provide: AnalyzedSqlService, useValue: analyzedSqlServiceStub({
          getTable: () => of(tableWithoutSchema),
          getSqlForTableAndJoin: () => of(tableWithoutSchema)
        }) }
      ]
    }).compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(TableViewComponent);
    fixture.detectChanges();
  });

  /**
   * Regression: the header and graph link rendered from the fetched payload's
   * schema echo, showing ".permissions" and a schema-less graph link when the
   * echo was empty. The route is the page's identity — render from it.
   */
  it('renders the header from the route params, not the payload echo', () => {
    const header = fixture.nativeElement.querySelector('h2 a');
    expect(header?.textContent?.trim()).toBe('app_main.permissions');
    expect(header?.getAttribute('href')).toContain('/table/app_main/permissions');
  });

  it('holds the route identity for outbound links (graph, tags, drill-downs)', () => {
    expect(fixture.componentInstance.schema).toBe('app_main');
    expect(fixture.componentInstance.name).toBe('permissions');
  });
});
