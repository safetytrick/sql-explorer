import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

const endpoint = '/api/sql-explorer/';

export interface TableNode {
  name: string;
  columnCount: number | null;
  queryCount: number;
  warn: boolean;
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

export interface GraphNode {
  schema: string;
  table: string;
  columnCount: number | null;
  queryCount: number;
  warn: boolean;
  selfJoin: boolean;
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

export interface GraphV2 {
  nodes: GraphNode[];
  fkEdges: GraphFkEdge[];
  coQueryEdges: GraphCoQueryEdge[];
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

  getTableStructure(schema: string, name: string): Observable<{ indices: TableIndex[] } | null> {
    return this.http.get<{ indices: TableIndex[] } | null>(`${endpoint}metadata/table/${schema}/${name}`);
  }

  getSchemaGraph(schema: string): Observable<GraphV2> {
    return this.http.get<GraphV2>(`${endpoint}graph/v2/schema/${schema}`);
  }
}
