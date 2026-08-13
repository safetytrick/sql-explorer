import {Injectable} from '@angular/core';
import {HttpClient, HttpParams} from '@angular/common/http';
import {Observable} from 'rxjs';
import {
  CleanedSql,
  GroupedTableRelationships,
  NormalizedQuery,
  SchemaRollup,
  SqlProfile,
  Table,
  TableRelationships,
  TableRollup,
  TableStructure,
  Tag
} from './api-types';

const endpoint = '/api/sql-explorer/';

@Injectable()
export class AnalyzedSqlService {

  constructor(private http: HttpClient) {}

  getTables(): Observable<TableRollup[]> {
    return this.http.get<TableRollup[]>(endpoint + 'tables');
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

  autoCompleteTags(text: string): Observable<string[]> {
    const params: HttpParams = new HttpParams().set('value', String(text));
    return this.http.get<string[]>(endpoint + 'tags/names', {params: params});
  }

  addTag(name: string, path: string): Observable<Tag> {
    return this.http.post<Tag>(endpoint + 'tags', {name: name, path: path});
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

  getTableGraph(): Observable<TableRelationships[]> {
    return this.http.get<TableRelationships[]>(endpoint + 'graph/all');
  }

  getTableGraphForSchema(schema: string): Observable<TableRelationships[]> {
    return this.http.get<TableRelationships[]>(endpoint + 'graph/schema/' + schema);
  }

  getTableGraphForTable(schema: string, table: string, depth: number): Observable<TableRelationships[]> {
    const params: HttpParams = new HttpParams().set('depth', String(depth));
    return this.http.get<TableRelationships[]>(endpoint + 'graph/schema/' + schema + '/table/' + table, {params: params});
  }

  getTableGraphForTag(tag: string, depth: number): Observable<GroupedTableRelationships[]> {
    const params: HttpParams = new HttpParams().set('depth', String(depth)).set('threshold', '100');
    return this.http.get<GroupedTableRelationships[]>(endpoint + 'graph/by-tag/' + tag, {params: params});
  }

  getTablesForSchema(schema: string): Observable<TableRollup[]> {
    return this.http.get<TableRollup[]>(endpoint + 'tables/' + schema);
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
}
