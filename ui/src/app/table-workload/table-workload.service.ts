import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';

const endpoint = '/api/sql-explorer/';

/** `TableWorkloadService.WriteStatement`: one writing statement, rates as daily averages. */
export interface WriteStatement {
  digest: string;
  type: string;
  query: string;
  /** Names other tables too; its counts are the statement's, not only this table's. */
  multiTable: boolean;
  executions: number;
  rowsAffected: number;
  executionsPerDay: number;
  rowsPerDay: number;
  /** lastSeen − firstSeen in days, unfloored; null when unknown. */
  windowDays: number | null;
  firstSeen: string | null;
  lastSeen: string | null;
}

export interface WriteTypeTotal {
  type: string;
  statements: number;
  executions: number;
  rowsAffected: number;
  executionsPerDay: number;
  rowsPerDay: number;
}

/** `/table/{s}/{t}/write-rate` (sqlx-n5j.10). Zeros for a table nothing writes. */
export interface WriteRate {
  executions: number;
  rowsAffected: number;
  executionsPerDay: number;
  rowsPerDay: number;
  from: string | null;
  to: string | null;
  byType: WriteTypeTotal[];
  statements: WriteStatement[];
}

export interface LockStatement {
  digest: string;
  type: string | null;
  query: string;
  executions: number;
  executionsPerDay: number;
  averageTimeMs: number;
  maxTimeMs: number;
  totalTimeMs: number;
  lockTimeMs: number;
  windowDays: number | null;
}

/** `/table/{s}/{t}/lock-risk` (sqlx-n5j.9). Statements ranked by max time, cut at 20. */
export interface LockRisk {
  statementCount: number;
  longThresholdMs: number;
  longStatements: number;
  longExecutionsPerDay: number;
  maxTimeMs: number;
  maxAverageTimeMs: number;
  /** Always false: the captured statistics carry no time-of-day breakdown. */
  quietHoursAvailable: boolean;
  statements: LockStatement[];
}

@Injectable({ providedIn: 'root' })
export class TableWorkloadService {

  constructor(private http: HttpClient) { }

  getWriteRate(schema: string, table: string): Observable<WriteRate> {
    return this.http.get<WriteRate>(`${endpoint}table/${schema}/${table}/write-rate`);
  }

  getLockRisk(schema: string, table: string): Observable<LockRisk> {
    return this.http.get<LockRisk>(`${endpoint}table/${schema}/${table}/lock-risk`);
  }
}

/** A daily rate, short: 0.03 → "<0.1", 3.25 → "3.3", 1480 → "1.5k". */
export function fmtPerDay(value: number | null | undefined): string {
  if (!value) { return '0'; }
  if (value < 0.1) { return '<0.1'; }
  if (value < 10) { return value.toFixed(1).replace(/\.0$/, ''); }
  if (value < 1000) { return Math.round(value).toString(); }
  const [divisor, suffix] = value >= 1e9 ? [1e9, 'B'] : value >= 1e6 ? [1e6, 'M'] : [1e3, 'k'];
  const scaled = value / divisor;
  return (scaled >= 100 ? Math.round(scaled).toString() : scaled.toFixed(1).replace(/\.0$/, '')) + suffix;
}

/** A statement time: 0.4 → "0.4 ms", 850 → "850 ms", 1500 → "1.5 s", 192000 → "3.2 min". */
export function fmtTime(ms: number | null | undefined): string {
  if (!ms) { return '0'; }
  if (ms < 1) { return `${ms.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')} ms`; }
  if (ms < 1000) { return `${Math.round(ms)} ms`; }
  if (ms < 60_000) { return `${(ms / 1000).toFixed(1).replace(/\.0$/, '')} s`; }
  if (ms < 3_600_000) { return `${(ms / 60_000).toFixed(1).replace(/\.0$/, '')} min`; }
  return `${(ms / 3_600_000).toFixed(1).replace(/\.0$/, '')} h`;
}

/** A window in days: 0.25 → "6 h", 118.4 → "118 d"; null → "?". */
export function fmtWindow(days: number | null | undefined): string {
  if (days === null || days === undefined) { return '?'; }
  if (days < 1) { return `${Math.max(1, Math.round(days * 24))} h`; }
  return `${Math.round(days)} d`;
}
