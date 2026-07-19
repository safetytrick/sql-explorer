import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ImportedSqlSource, PersistedSource, SourceRefRequest, SqlImportStats } from './api-types';

const endpoint = '/api/sql-explorer/';

@Injectable({
  providedIn: 'root'
})
export class SourceService {

  constructor(private http: HttpClient) { }

  getActiveSources(): Observable<ImportedSqlSource[]> {
    return this.http.get<ImportedSqlSource[]>(endpoint + 'sources/active');
  }

  getAllSources(): Observable<PersistedSource[]> {
    return this.http.get<PersistedSource[]>(endpoint + 'sources');
  }

  loadSources(sources: SourceRefRequest[]): Observable<SqlImportStats> {
    return this.http.post<SqlImportStats>(endpoint + 'sources/load', sources);
  }
}
