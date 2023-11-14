import { Injectable } from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs/internal/Observable';

const endpoint = '/api/sql-explorer/';

@Injectable({
  providedIn: 'root'
})
export class SourceService {

  constructor(private http: HttpClient) { }

  getActiveSources(): Observable<any> {
    return this.http.get(endpoint + 'sources/active');
  }

  getAllSources(): Observable<any> {
    return this.http.get(endpoint + 'sources');
  }

  loadSources(sources): Observable<any> {
    return this.http.post(endpoint + 'sources/load', sources);
  }
}
