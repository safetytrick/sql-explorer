import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import {QuerySearchRequest} from './query-search.service';
import type {ColumnRef, TableRef} from './refs';
import type {TableStorageStats} from './api-types';

const endpoint = '/api/sql-explorer/';

export interface TableNode {
  name: string;
  columnCount: number | null;
  queryCount: number;
  warn: boolean;
  view: boolean;
  /**
   * False when the schema metadata does not describe this table — the node
   * exists only because a query named it. View-swap artifacts
   * (`stat_get_user_list_<hex>`) that have since been dropped, Hibernate's
   * `ht_*` temp tables, and MySQL data-dictionary tables all land here. Not
   * the same as a null `columnCount`, which a real table also gets when its
   * column list did not load.
   */
  inSchema: boolean;
  /**
   * When a query last touched this table in a period the loaded corpus no
   * longer covers, from an archived capture taken before a reset.
   *
   * <p>Set only for a table with no queries in the current run — the only case
   * where it changes the answer. Null is the ordinary value and means the
   * archive knows nothing extra, NOT that the table was never used: before any
   * reset there is no earlier run to consult (sqlx-qwn).
   */
  archivedLastSeen?: string | null;
}

export interface SchemaNode {
  name: string;
  tables: TableNode[];
}

export interface SchemaTree {
  schemas: SchemaNode[];
}

export interface TableStats {
  distinctQueries: number;
  totalExecutions: number;
  totalTimeMs: number;
  readExecutions: number;
  writeExecutions: number;
}

export interface ColumnUsage {
  name: string;
  type: string;
  size: number;
  nullable: boolean;
  autoIncrement: boolean;
  primaryKey: boolean;
  foreignKey: boolean;
  characterSet: string | null;
  collation: string | null;
  roles: string[];
  queries: number;
  /**
   * Qualifies the `criteria` role: per comparison kind (`equality`, `range`,
   * `in`, `like`, `not like`, ...), the distinct queries comparing the column
   * that way (sqlx-n5j.7). Optional: absent from a backend older than it.
   */
  comparisons?: Record<string, number>;
  /** LIKE / NOT LIKE usage under criteria; null unless some query pattern-matches. */
  like?: LikeUsage | null;
}

/**
 * Distinct queries matching a column with LIKE or NOT LIKE, those whose
 * pattern is known to lead with a wildcard, and those whose pattern could not
 * be read (usually a `?` placeholder).
 */
export interface LikeUsage {
  queries: number;
  leadingWildcard: number;
  unknownWildcard: number;
}

/**
 * One index and how queries hint it (`GET /table/{s}/{t}/indexes`). `hints`
 * holds the lower-cased actions — `force`, `ignore`, `use` — and `queries` the
 * distinct queries naming the index in any of them. `exists: false` is an
 * index a query hints that the table does not have: MySQL rejects such a
 * statement (error 1176), so those queries are failing (sqlx-yj0).
 */
export interface IndexUsage {
  name: string;
  primary: boolean;
  unique: boolean;
  columns: string[];
  exists: boolean;
  hints: string[];
  queries: number;
}

export interface CoQueriedTable {
  schema: string;
  table: string;
  queries: number;
  executions: number;
  avgTimeMs: number | null;
  joined: boolean;
  joinTypes: string[];
  fkDirection: 'out' | 'in' | null;
}

export interface ForeignKeyColumnDetail {
  fromColumn: string;
  fromType: string | null;
  toColumn: string;
  toType: string | null;
  typeMismatch: boolean;
}

export interface ForeignKeyDetail {
  direction: 'out' | 'in';
  constraintName: string | null;
  onUpdate: string | null;
  onDelete: string | null;
  otherSchema: string;
  otherTable: string;
  columns: ForeignKeyColumnDetail[];
}

export interface TableIndex {
  name: string;
  unique: boolean;
  columns: string[];
}

/**
 * A view column's source, as sent: a `"schema.table.column"` string. It was
 * declared as an object, so every lineage link read `undefined` and the table
 * lens rendered none of them. See refs.ts.
 */
export type ViewColumnSource = ColumnRef;

/** One view output column and the base columns it derives from. */
export interface ViewColumnMeta {
  name: string;
  sources: ViewColumnSource[];
  computed: boolean;
}

/**
 * Just enough of a metadata column to carry its comment.
 *
 * <p>The lens's column table is built from `ColumnUsage`, which is about how
 * queries use a column and carries no comment. The comment lives here, on the
 * metadata payload the lens already fetches for the table comment — so the two
 * are joined by name rather than by adding a field to the usage shape
 * (sqlx-gw5).
 */
export interface TableMetaColumn {
  columnName: string;
  /** MySQL returns '' for no comment, not null. */
  remarks: string | null;
  /** The rest of the metadata column, read by the graph's Columns tab. */
  typeName?: string;
  columnSize?: number;
  /** "YES" or "NO", as JDBC reports it. */
  isNullable?: string;
  ordinal?: number;
}

/** Subset of the metadata TableStructure the lens reads. */
export interface TableMeta {
  indices: TableIndex[];
  type: string | null;
  /** Table comment, when the schema carries one. */
  remarks: string | null;
  /** Per-column comments; the usage shape has none. */
  columns: TableMetaColumn[] | null;
  underlyingTables: TableRef[] | null;
  viewColumns: ViewColumnMeta[] | null;
  /** Size and row estimate per server (sqlx-n5j.2); absent before it was kept. */
  stats?: TableStorageStats[] | null;
}

export interface GraphNode {
  schema: string;
  table: string;
  columnCount: number | null;
  queryCount: number;
  warn: boolean;
  selfJoin: boolean;
  view: boolean;
  /** The key columns, in table order: what an ERD card lists (sqlx-929). */
  columns?: GraphKeyColumn[];
  /** Executions of the queries counted in queryCount (sqlx-7o0). */
  executions?: number;
  /** Their total time in ms, each query counted in full against every table it touches. */
  queryTimeMs?: number;
  /** Data plus index bytes over the servers reporting it; null when none does. */
  sizeBytes?: number | null;
  /** Of which the rows (DATA_LENGTH), and the secondary indexes (INDEX_LENGTH); null when unreported. */
  dataBytes?: number | null;
  indexBytes?: number | null;
}

export interface GraphFkEdge {
  fromSchema: string;
  fromTable: string;
  toSchema: string;
  toTable: string;
  label: string;
  mismatch: boolean;
  /** Some query joins the two tables on the key's columns; false means it only enforces integrity. */
  joined?: boolean;
  /** Every column pair of the key, `a` the referencing side. */
  pairs?: GraphColumnPair[];
}

/** Two tables joined in at least one query, from analysis (sqlx-aoy.3). */
export interface GraphJoinEdge {
  aSchema: string;
  aTable: string;
  bSchema: string;
  bTable: string;
  /** Distinct column pairs, `a_col=b_col`. */
  columns: string[];
  queries: number;
  executions: number;
  /** The same pairs, in the same order, with each end's uniqueness. */
  pairs?: GraphColumnPair[];
}

export interface GraphCoQueryEdge {
  aSchema: string;
  aTable: string;
  bSchema: string;
  bTable: string;
  queries: number;
  executions: number;
}

/** One row of an ERD card: a primary key, foreign key or joined-on column (sqlx-929). */
export interface GraphKeyColumn {
  name: string;
  /** As MySQL names it; null when the table's structure is not known. */
  type: string | null;
  nullable: boolean;
  primary: boolean;
  /** The column alone is a unique index, the primary key included. */
  unique: boolean;
  /** A declared foreign key starts at it. */
  foreignKey: boolean;
}

/**
 * One column pair of a relationship and what the schema says about each end.
 * `aUnique` is true when a unique index is covered by the columns joined on
 * that side and includes this one; null when the table's structure is unknown.
 */
export interface GraphColumnPair {
  a: string;
  b: string;
  aUnique: boolean | null;
  bUnique: boolean | null;
  aNullable: boolean | null;
  bNullable: boolean | null;
  /** The side a declared foreign key on exactly this pair starts from. */
  declared: 'a' | 'b' | null;
}

/** A view selecting from one of its base tables. */
export interface GraphViewEdge {
  fromSchema: string;
  fromTable: string;
  toSchema: string;
  toTable: string;
}

export interface GraphV2 {
  nodes: GraphNode[];
  fkEdges: GraphFkEdge[];
  coQueryEdges: GraphCoQueryEdge[];
  viewEdges: GraphViewEdge[];
  joinEdges?: GraphJoinEdge[];
}

@Injectable({ providedIn: 'root' })
export class SchemaLensService {

  constructor(private http: HttpClient) { }

  getSchemaTree(): Observable<SchemaTree> {
    return this.http.get<SchemaTree>(endpoint + 'schema-tree');
  }

  getTableStats(schema: string, name: string): Observable<TableStats> {
    return this.http.get<TableStats>(`${endpoint}table/${schema}/${name}/stats`);
  }

  getTableColumns(schema: string, name: string): Observable<ColumnUsage[]> {
    return this.http.get<ColumnUsage[]>(`${endpoint}table/${schema}/${name}/columns`);
  }

  getTableIndexes(schema: string, name: string): Observable<IndexUsage[]> {
    return this.http.get<IndexUsage[]>(`${endpoint}table/${schema}/${name}/indexes`);
  }

  getCoQueried(schema: string, name: string): Observable<CoQueriedTable[]> {
    return this.http.get<CoQueriedTable[]>(`${endpoint}table/${schema}/${name}/co-queried`);
  }

  getForeignKeys(schema: string, name: string): Observable<ForeignKeyDetail[]> {
    return this.http.get<ForeignKeyDetail[]>(`${endpoint}metadata/table/${schema}/${name}/foreign-keys`);
  }

  getTableStructure(schema: string, name: string): Observable<TableMeta | null> {
    return this.http.get<TableMeta | null>(`${endpoint}metadata/table/${schema}/${name}`);
  }

  /**
   * The graph of the queries a search matches. `allTables` also draws every
   * table of the schemas the search is scoped to, queried or not.
   */
  getSearchGraph(request: QuerySearchRequest | null, allTables = false): Observable<GraphV2> {
    return this.http.post<GraphV2>(`${endpoint}graph/v2/search${allTables ? '?allTables=true' : ''}`, request ?? {});
  }
}
