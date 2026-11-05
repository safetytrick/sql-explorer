import {Injectable} from '@angular/core';
import {HttpClient, HttpParams} from '@angular/common/http';
import {Observable} from 'rxjs';
import type {TagTarget} from './tag-path';
import {
  CleanedSql,
  ExplainResult,
  NormalizedQuery,
  QueryExample,
  SchemaRollup,
  SqlProfile,
  Table,
  TableRollup,
  TableStructure,
  Tag
} from './api-types';

const endpoint = '/api/sql-explorer/';

/** Spring binds a repeated `columns` to its list parameter; none sent is no constraint. */
function columnsParam(columns: string[]): HttpParams {
  return columns.reduce((params, column) => params.append('columns', column), new HttpParams());
}

@Injectable()
export class AnalyzedSqlService {

  constructor(private http: HttpClient) {}

  /** Tables the queries reference; with `columns`, only those having every one (sqlx-v86). */
  getTables(columns: string[] = []): Observable<TableRollup[]> {
    return this.http.get<TableRollup[]>(endpoint + 'tables', { params: columnsParam(columns) });
  }

  getSql(id: string): Observable<SqlProfile> {
    return this.http.get<SqlProfile>(endpoint + 'analyzed-sql/' + id);
  }

  getTable(schema: string, name: string, column: string): Observable<Table> {
    const columnPath = column ? '/column/' + column : '';
    return this.http.get<Table>(endpoint + 'table/' + schema + '/' + name + columnPath);
  }

  /** Null when the loaded schema doesn't know the table (the endpoint returns an empty body). */
  getTableStructure(schema: string, name: string): Observable<TableStructure | null> {
    return this.http.get<TableStructure | null>(endpoint + 'metadata/table/' + schema + '/' + name);
  }

  getTableCreateStatement(schema: string, name: string): Observable<string> {
    return this.http.get(endpoint + 'metadata/table/' + schema + '/' + name + '/create',
      {responseType: 'text'});
  }

  cleanQuery(query: string): Observable<CleanedSql> {
    return this.http.post<CleanedSql>(endpoint + 'util/clean', query);
  }

  normalizeQuery(query: string): Observable<NormalizedQuery> {
    return this.http.post<NormalizedQuery>(endpoint + 'util/normalize', query);
  }

  getTags(path: string): Observable<Tag[]> {
    const params: HttpParams = new HttpParams().set('path', String(path));
    return this.http.get<Tag[]>(endpoint + 'tags/path', {params: params});
  }

  getTagsForPaths(paths: string[]): Observable<Record<string, Tag[]>> {
    return this.http.post<Record<string, Tag[]>>(endpoint + 'tags/path', paths);
  }

  /**
   * The tags on each target, answered POSITIONALLY — one entry per target, in
   * the order sent.
   *
   * <p>Replaces keying a batch by path string, which could neither ask about nor
   * return a tag on a table whose name contains `/` (sqlx-3ai). Callers zip the
   * answer against the rows they sent.
   */
  getTagsForTargets(targets: TagTarget[]): Observable<Tag[][]> {
    return this.http.post<Tag[][]>(endpoint + 'tags/targets', targets);
  }

  autoCompleteTags(text: string): Observable<string[]> {
    const params: HttpParams = new HttpParams().set('value', String(text));
    return this.http.get<string[]>(endpoint + 'tags/names', {params: params});
  }

  addTag(name: string, target: TagTarget): Observable<Tag> {
    return this.http.post<Tag>(endpoint + 'tags', {name, target});
  }

  removeTag(id: number): Observable<void> {
    return this.http.delete<void>(endpoint + 'tag/' + id);
  }

  getSqlByTag(tag: string): Observable<SqlProfile[]> {
    return this.http.get<SqlProfile[]>(endpoint + 'analyzed-sql/tag/' + tag);
  }

  getTagsByType(type: string): Observable<string[]> {
    return this.http.get<string[]>(endpoint + 'tags/type/' + type);
  }

  getTablesByTag(tag: string): Observable<TableRollup[]> {
    return this.http.get<TableRollup[]>(endpoint + 'tables/tag/' + tag);
  }

  getSchemasByTag(tag: string): Observable<SchemaRollup[]> {
    return this.http.get<SchemaRollup[]>(endpoint + 'schemas/tag/' + tag);
  }

  getTablesForSchema(schema: string, columns: string[] = []): Observable<TableRollup[]> {
    return this.http.get<TableRollup[]>(endpoint + 'tables/' + schema, { params: columnsParam(columns) });
  }

  getSqlForTableAndJoin(schema: string, table: string, otherSchema: string, otherTable: string): Observable<Table> {
    return this.http.get<Table>(endpoint + 'table/' + schema + '/' + table + '/join/' + otherSchema + '/' + otherTable);
  }

  getTablesWithColumn(column: string): Observable<TableRollup[]> {
    return this.http.get<TableRollup[]>(endpoint + 'tables/with-column/' + column);
  }

  getSchemas(): Observable<SchemaRollup[]> {
    return this.http.get<SchemaRollup[]>(endpoint + 'schemas');
  }

  // ── query examples ──

  /**
   * Submit an example. Deliberately posts the *statement*, never a digest: the
   * server derives the digest from the text, so the association cannot be
   * asserted by the caller. `sessionSchema` is optional — omitted, the server
   * infers it, and answers 409 with candidates when the statement does not
   * determine one.
   */
  createQueryExample(queryText: string, sessionSchema: string | null, description: string | null):
      Observable<QueryExample> {
    return this.http.post<QueryExample>(endpoint + 'query-examples', {
      queryText,
      sessionSchema: sessionSchema || null,
      description: description || null
    });
  }

  /** Examples for a digest, most-recently-added first. */
  getQueryExamples(digest: string): Observable<QueryExample[]> {
    return this.http.get<QueryExample[]>(endpoint + 'query-examples/' + digest);
  }

  /** Plan a stored example on the active environment's server. */
  explainQueryExample(exampleId: string): Observable<ExplainResult> {
    return this.http.post<ExplainResult>(endpoint + 'query-examples/' + exampleId + '/explain', {});
  }
}
