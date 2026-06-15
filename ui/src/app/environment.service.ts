import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable } from 'rxjs';

const endpoint = '/api/sql-explorer/';
const STORAGE_KEY = 'sql-explorer.environment';

export interface EnvironmentSummary {
  name: string;
  servers: string[];
}

export interface EnvironmentList {
  defaultEnvironment: string;
  environments: EnvironmentSummary[];
}

@Injectable({
  providedIn: 'root'
})
export class EnvironmentService {

  /** Currently selected environment name (null = use the backend default). */
  readonly current$ = new BehaviorSubject<string | null>(localStorage.getItem(STORAGE_KEY));

  constructor(private http: HttpClient) { }

  getEnvironments(): Observable<EnvironmentList> {
    return this.http.get<EnvironmentList>(endpoint + 'environments');
  }

  getCurrent(): string | null {
    return this.current$.value;
  }

  setCurrent(name: string | null): void {
    if (name) {
      localStorage.setItem(STORAGE_KEY, name);
    } else {
      localStorage.removeItem(STORAGE_KEY);
    }
    this.current$.next(name);
  }
}
