import {of} from 'rxjs';
import {AnalyzedSqlService} from '../analyzed-sql.service';

/**
 * Complete AnalyzedSqlService test double: every method resolves to an empty
 * result, so a spec can render a component whose *child* components (tag
 * views, summaries, links…) call service methods the spec doesn't care
 * about. Spread in overrides for the calls the spec asserts on:
 *
 * <pre>
 * { provide: AnalyzedSqlService,
 *   useValue: analyzedSqlServiceStub({ getTable: () => of(myTable) }) }
 * </pre>
 *
 * Keep this in sync when AnalyzedSqlService grows a method — a missing entry
 * surfaces as "this.sqlService.X is not a function" in unrelated specs.
 */
export function analyzedSqlServiceStub(overrides: object = {}): AnalyzedSqlService {
  const empty = {
    getTables: () => of([]),
    getSql: () => of(null),
    getTable: () => of(null),
    getTableStructure: () => of(null),
    getTableCreateStatement: () => of(''),
    cleanQuery: () => of(null),
    normalizeQuery: () => of(null),
    getTags: () => of([]),
    getTagsForPaths: () => of({}),
    autoCompleteTags: () => of([]),
    addTag: () => of(null),
    removeTag: () => of(undefined),
    getSqlByTag: () => of([]),
    getTagsByType: () => of([]),
    getTablesByTag: () => of([]),
    getSchemasByTag: () => of([]),
    getTableGraph: () => of([]),
    getTableGraphForSchema: () => of([]),
    getTableGraphForTable: () => of([]),
    getTableGraphForTag: () => of([]),
    getTablesForSchema: () => of([]),
    getSqlForTableAndJoin: () => of(null),
    getTablesWithColumn: () => of([]),
    getSchemas: () => of([])
  };
  return { ...empty, ...overrides } as unknown as AnalyzedSqlService;
}
