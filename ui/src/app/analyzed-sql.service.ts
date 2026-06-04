import {Injectable} from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import {Observable} from 'rxjs';

// todo, configure me
const endpoint = '/api/sql-explorer/';

@Injectable()
export class AnalyzedSqlService {

  constructor(private http: HttpClient) {}

  getTables(): Observable<any> {
    return this.http.get(endpoint + 'tables');
  }

  getSql(id: string): Observable<any> {
    return this.http.get(endpoint + 'analyzed-sql/' + id);
  }

  getTable(schema: string, name: string, column: string): Observable<any> {
    const columnPath = column ? '/column/' + column : '';
    return this.http.get(endpoint + 'table/' + schema + '/' + name + columnPath);
  }

  getReportSql(name: string, limit: number): Observable<any> {
    const params: HttpParams = new HttpParams().set('limit', String(limit));

    return this.http.get(endpoint + 'analyzed-sql/reports/' + name, {params: params});
  }

  getTableStructure(schema: string, name: string) {
    return this.http.get(endpoint + 'metadata/table/' + schema + '/' + name);
  }

  getTableCreateStatement(schema: string, name: string): Observable<string> {
    return this.http.get(endpoint + 'metadata/table/' + schema + '/' + name + '/create',
      {responseType: 'text'});
  }

  cleanQuery(query: string) {
    return this.http.post(endpoint + 'util/clean', query);
  }

  normalizeQuery(query: string) {
    return this.http.post(endpoint + 'util/normalize', query);
  }

  getTags(path: string): Observable<any> {
    const params: HttpParams = new HttpParams().set('path', String(path));
    return this.http.get(endpoint + 'tags/path', {params: params});
  }

  autoCompleteTags(text: string) {
    const params: HttpParams = new HttpParams().set('value', String(text));
    return this.http.get(endpoint + 'tags/names', {params: params});
  }

  addTag(name: string, path: string) {
    return this.http.post(endpoint + 'tags', {name: name, path: path});
  }

  removeTag(id: number) {
    return this.http.delete(endpoint + 'tag/' + id);
  }

  getSqlByTag(tag: string) {
    return this.http.get(endpoint + 'analyzed-sql/tag/' + tag);
  }

  getTagsByType(type: string) {
    return this.http.get(endpoint + 'tags/type/' + type);
  }

  getTablesByTag(tag: string) {
    return this.http.get(endpoint + 'tables/tag/' + tag);
  }

  getTableGraph() {
    return this.http.get(endpoint + 'graph/all');
  }

  getTableGraphForSchema(schema: string) {
    return this.http.get(endpoint + 'graph/schema/' + schema);
  }

  getTableGraphForTable(schema: string, table: string, depth: number) {
    const params: HttpParams = new HttpParams().set('depth', String(depth));
    return this.http.get(endpoint + 'graph/schema/' + schema + '/table/' + table, {params: params});
  }

  getTableGraphForTag(tag: string, depth: number) {
    const params: HttpParams = new HttpParams().set('depth', String(depth)).set('threshold', '100');
    return this.http.get(endpoint + 'graph/by-tag/' + tag, {params: params});
  }

  getTablesForSchema(schema: string) {
    return this.http.get(endpoint + 'tables/' + schema);
  }

  getSqlForTableAndJoin(schema: string, table: string, otherSchema: string, otherTable: string) {
    return this.http.get(endpoint + 'table/' + schema + '/' + table + '/join/' + otherSchema + '/' + otherTable);
  }

  getTablesWithColumn(column: string) {
      return this.http.get(endpoint + 'tables/with-column/' + column);
  }

  getSchemas() {
    return this.http.get(endpoint + 'schemas');
  }
}
