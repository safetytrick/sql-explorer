/**
 * Shared response DTOs mirroring the backend types (Avro `models/*.avdl`,
 * `model/*`, `schema/*`, graph and commentary records). Hand-maintained — keep
 * in sync with the Java records they mirror. Times sourced from `BigDecimal`
 * fields are serialized as numbers (picoseconds); Avro `local_timestamp_ms`
 * fields serialize as ISO strings.
 */
import {SqlImportStats} from './environment.service';

export { SqlImportStats };

export type QueryActivityStatus = 'ACTIVE' | 'DORMANT' | 'UNKNOWN';

export interface TableReference {
  schemaName: string | null;
  tableName: string;
  normalizedTableName: string;
  normalizedSchemaName: string | null;
}

export interface ColumnReference {
  table: TableReference;
  column: string;
  normalizedColumn: string;
}

export interface JoinColumns {
  left: ColumnReference;
  right: ColumnReference;
  joinType: string | null;
}

export interface RuntimeMetrics {
  fullTableScan: boolean;
  executed: number;
  errors: number;
  warnings: number;
  totalTime: number;   // picoseconds
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
  digest: string;
  lastSeen: string | null;
  firstSeen: string | null;
  lockTime: number;
  rowsAffected: number;
  averageRowsAffected: number;
  digestList: string[];
}

/** Full analyzed-query profile (Avro SqlProfile). */
export interface SqlProfile {
  id: string;
  query: string;
  tables: TableReference[];
  type: string;
  joinColumns: JoinColumns[];
  criteriaColumns: ColumnReference[];
  selectColumns: ColumnReference[];
  groupByColumns: ColumnReference[];
  generatedSource: string | null;
  truncated: boolean;
  analysisFailed: boolean;
  analysisSkipped: boolean;
  isDml: boolean;
  runtimeMetrics: RuntimeMetrics | null;
  digest: string;
  normalizedDigest: string;
  schemaName: string;
  schemas: string[];
  mismatchedJoins: JoinColumns[];
  lineNumber: number | null;
  analysisFailureReason: string | null;
  sourceFileName: string | null;
  sourcedFiles: string[];
  queryActivityStatus: QueryActivityStatus;
  modifiedForAnalysis: boolean;
  isMultiSchema: boolean;
}

export interface Tag {
  id: number;
  name: string;
  path: string;
  type: string;
  author: unknown | null;
  createdOn: string | null;
}

export interface SchemaRollup {
  name: string;
  numberOfQueries: number;
  totalQueryTime: number;
  tags: Tag[];
}

export interface TableRollup {
  name: string;
  numberOfQueries: number;
  totalQueryTime: number;
  schema: string;
  tags: Tag[];
  /** True when the schema metadata knows this "table" is a view. */
  view: boolean;
}

export interface TableColumn {
  schema: string;
  table: string;
  columnName: string;
  type: string;        // JDBCType enum name
  typeName: string;
  columnSize: number;
  decimalDigits: number;
  radix: number;
  remarks: string;
  columnDefault: string;
  octetLength: number;
  ordinal: number;
  isNullable: string;
  isAutoIncrement: string;
  isGenerated: string;
  characterSet: string;
  collation: string;
}

export interface TableIndex {
  schema: string;
  unique: boolean;
  identifier: string;
  name: string;
  indexType: string;
  direction: string;
  cardinality: number;
  pages: number;
  filterCondition: string;
  columns: string[];
}

export interface ForeignKey {
  from: ColumnReference[];
  to: ColumnReference[];
  constraintName: string | null;
  onUpdate: string | null;
  onDelete: string | null;
}

export interface TableStructure {
  schema: string;
  name: string;
  type: string;
  remarks: string;
  foreignKeys: ForeignKey[];
  indices: TableIndex[];
  columns: TableColumn[];
  /** Base tables a view selects from; null for base tables. */
  underlyingTables: TableReference[] | null;
}

/** Per-table profile view (model/Table). */
export interface Table {
  name: string;
  numberOfQueries: number;
  totalQueryTime: number;
  queries: SqlProfile[];
  structure: TableStructure | null;
  schema: string;
  foreignKeyTables: TableReference[];
  joinTables: TableReference[];
  relatedTables: TableReference[];
}

export interface CleanedSql {
  sql: string;
  truncated: boolean;
  modified: boolean;
}

export interface NormalizedQuery {
  text: string;
}

export interface SqlSource {
  createdOn: string;
  sourceRef: string;
}

export interface ImportedSqlSource {
  source: SqlSource;
  status: string;
  importStats: SqlImportStats;
}

export interface PersistedSource {
  source: {
    id: number;
    sourceRef: string;
    sourceUrl: string;
    md5Hash: string;
    createdOn: string;
  };
  environmentName: string;
}

/** POST /sources/load body element (SourceRefRequest). */
export interface SourceRefRequest {
  sourceRef: string;
}

export interface TableRelationships {
  table: TableReference;
  foreignKeyTables: TableReference[];
  joinTables: TableReference[];
  relatedTables: TableReference[];
}

export interface GroupedTableRelationships extends TableRelationships {
  group: string;
}

export interface CrossSchemaTable {
  query: string;
  id: string;
  analysisUrl: string;
  grafanaUrl: string;
  rundeckQuery: string;
  tables: string;
}
