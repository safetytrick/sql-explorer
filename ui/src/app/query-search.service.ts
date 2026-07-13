import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

const endpoint = '/api/sql-explorer/';

export interface Range {
  min?: number | null;
  max?: number | null;
}

export interface DateRange {
  after?: string | null;   // ISO date
  before?: string | null;
}

export type Flag = 'ANY' | 'YES' | 'NO';
export type Activity = 'ANY' | 'ACTIVE' | 'DORMANT';
export type TagMode = 'ANY' | 'ALL';
export type SortKey = 'AVG_TIME' | 'MAX_TIME' | 'AVG_ROWS_SCANNED' | 'SELECTIVITY' | 'LAST_SEEN';

export interface QuerySearchRequest {
  avgTimeMs?: Range | null;
  maxTimeMs?: Range | null;
  avgRowsScanned?: Range | null;
  selectivity?: Range | null;
  rowsSorted?: Range | null;
  activity?: Activity | null;
  firstSeen?: DateRange | null;
  lastSeen?: DateRange | null;
  tags?: string[] | null;
  tagMode?: TagMode | null;
  untaggedOnly?: boolean | null;
  fullScan?: Flag | null;
  tmpTable?: Flag | null;
  tmpDisk?: Flag | null;
  errors?: Flag | null;
  warnings?: Flag | null;
  analyzerWarnings?: boolean | null;
  analysisFailed?: boolean | null;
  cleanAnalysis?: boolean | null;
  sort?: SortKey | null;
  limit?: number | null;
  offset?: number | null;
}

export interface RuntimeMetrics {
  fullTableScan: boolean;
  executed: number;
  errors: number;
  warnings: number;
  totalTime: number;      // picoseconds
  maxTime: number;
  averageTime: number;
  rowsSent: number;
  averageRowsSent: number;
  rowsScanned: number;
  averageRowsScanned: number;
  tempTables: number;
  tempDiskTables: number;
  rowsSorted: number;
  sortMergePasses: number;
  firstSeen: string | null;
  lastSeen: string | null;
}

export interface QueryProfile {
  id: string;
  query: string;
  type: string;
  digest: string;
  normalizedDigest: string;
  queryActivityStatus: 'ACTIVE' | 'DORMANT' | 'UNKNOWN';
  analysisFailed: boolean;
  analysisFailureReason: string | null;
  mismatchedJoins: unknown[];
  runtimeMetrics: RuntimeMetrics | null;
}

export interface QueryTag {
  id: number;
  name: string;
  path: string;
}

export interface QueryHit {
  profile: QueryProfile;
  tags: QueryTag[];
}

export interface QuerySearchResult {
  total: number;
  results: QueryHit[];
}

@Injectable({ providedIn: 'root' })
export class QuerySearchService {

  constructor(private http: HttpClient) { }

  search(request: QuerySearchRequest): Observable<QuerySearchResult> {
    return this.http.post<QuerySearchResult>(endpoint + 'queries/search', request);
  }
}
