import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import {QuerySearchRequest} from './query-search.service';
import type {ColumnRef, TableRef} from './refs';

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

/** Subset of the metadata TableStructure the lens reads. */
export interface TableMeta {
  indices: TableIndex[];
  type: string | null;
  /** Table comment, when the schema carries one. */
  remarks: string | null;
  underlyingTables: TableRef[] | null;
  viewColumns: ViewColumnMeta[] | null;
}

export interface GraphNode {
  schema: string;
  table: string;
  columnCount: number | null;
  queryCount: number;
  warn: boolean;
  selfJoin: boolean;
  view: boolean;
}

export interface GraphFkEdge {
  fromSchema: string;
  fromTable: string;
  toSchema: string;
  toTable: string;
  label: string;
  mismatch: boolean;
}

export interface GraphCoQueryEdge {
  aSchema: string;
  aTable: string;
  bSchema: string;
  bTable: string;
  queries: number;
  executions: number;
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

  getCoQueried(schema: string, name: string): Observable<CoQueriedTable[]> {
    return this.http.get<CoQueriedTable[]>(`${endpoint}table/${schema}/${name}/co-queried`);
  }

  getForeignKeys(schema: string, name: string): Observable<ForeignKeyDetail[]> {
    return this.http.get<ForeignKeyDetail[]>(`${endpoint}metadata/table/${schema}/${name}/foreign-keys`);
  }

  getTableStructure(schema: string, name: string): Observable<TableMeta | null> {
    return this.http.get<TableMeta | null>(`${endpoint}metadata/table/${schema}/${name}`);
  }

  getSchemaGraph(schema: string): Observable<GraphV2> {
    return this.http.get<GraphV2>(`${endpoint}graph/v2/schema/${schema}`);
  }

  getTagGraph(tag: string): Observable<GraphV2> {
    return this.http.get<GraphV2>(`${endpoint}graph/v2/by-tag/${encodeURIComponent(tag)}`);
  }

  getSearchGraph(request: QuerySearchRequest | null): Observable<GraphV2> {
    return this.http.post<GraphV2>(`${endpoint}graph/v2/search`, request ?? {});
  }
}
