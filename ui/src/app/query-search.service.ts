import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import type {SqlProfile} from './api-types';

const endpoint = '/api/sql-explorer/';

export interface Range {
  min?: number | null;
  max?: number | null;
}

export interface DateRange {
  after?: string | null;   // ISO date-time (YYYY-MM-DDTHH:mm:ss); plain dates also accepted
  before?: string | null;  // plain dates expand server-side to the whole day
}

export type Flag = 'ANY' | 'YES' | 'NO';
export type Activity = 'ANY' | 'ACTIVE' | 'DORMANT';
export type TagMode = 'ANY' | 'ALL';
export type TableMode = 'ANY' | 'ALL';
export type SortKey = 'IMPACT' | 'AVG_TIME' | 'MAX_TIME' | 'AVG_ROWS_SCANNED' | 'SELECTIVITY' | 'LAST_SEEN';

export interface QuerySearchRequest {
  queryRegex?: string | null;   // regex over SqlProfile.query (case-insensitive, literal fallback)
  avgTimeMs?: Range | null;
  maxTimeMs?: Range | null;
  avgRowsScanned?: Range | null;
  selectivity?: Range | null;
  rowsSorted?: Range | null;
  activity?: Activity | null;
  types?: string[] | null;
  firstSeen?: DateRange | null;
  lastSeen?: DateRange | null;
  tags?: string[] | null;
  tagMode?: TagMode | null;
  untaggedOnly?: boolean | null;
  schemas?: string[] | null;
  excludeSchemas?: string[] | null;
  tables?: string[] | null;        // normalized "schema.table" keys
  tableMode?: TableMode | null;
  excludeTables?: string[] | null; // normalized "schema.table" keys, dropped from results
  columns?: string[] | null;       // "schema.table.column"; kept if the column joins or filters
  fullScan?: Flag | null;
  tmpTable?: Flag | null;
  tmpDisk?: Flag | null;
  errors?: Flag | null;
  warnings?: Flag | null;
  truncated?: Flag | null;
  multiSchema?: Flag | null;
  analyzerWarnings?: boolean | null;
    /** Only queries with at least one stored example — the ones that can be planned. */
  hasExamples?: boolean | null;
analysisFailed?: boolean | null;
  cleanAnalysis?: boolean | null;
  analysisSkipped?: boolean | null;
  failureKind?: 'ANALYZER' | 'PARSE' | null;
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

/** A block the normalizer collapsed: [start, end) in query text, seen count times. */
export interface RepeatedBlock {
  start: number;
  end: number;
  count: number;
}

/**
 * A query folded into this one because it normalized identically. Its
 * repeatedBlocks index into the *representative's* query text, so they can be
 * compared against it directly — only the counts differ.
 */
export interface MergedChild {
  digest: string;
  repeatedBlocks: RepeatedBlock[] | null;
  executed: number;
  averageTime: number;
}

/**
 * What a hit carries. `QuerySearchResult.QueryHit` is `record QueryHit(SqlProfile
 * profile, List<Tag> tags)`, so the wire really does send the whole profile —
 * this used to be a hand-narrowed subset, which meant a consumer wanting one
 * more field had to widen the type before it could read what was already there.
 */
export type QueryProfile = SqlProfile;

export interface QueryTag {
  id: number;
  name: string;
  path: string;
}

export interface QueryHit {
  profile: QueryProfile;
  tags: QueryTag[];
}

/** Per-table / per-schema match counts over the current filter (excluding the schema/table scope). */
export interface QueryFacets {
  tables: Record<string, number>;   // keyed by normalized "schema.table"
  schemas: Record<string, number>;  // keyed by normalized schema
}

export interface QuerySearchResult {
  total: number;
  results: QueryHit[];
  facets: QueryFacets;
}

@Injectable({ providedIn: 'root' })
export class QuerySearchService {

  constructor(private http: HttpClient) { }

  search(request: QuerySearchRequest): Observable<QuerySearchResult> {
    return this.http.post<QuerySearchResult>(endpoint + 'queries/search', request);
  }
}
