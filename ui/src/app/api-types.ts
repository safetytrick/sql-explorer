/**
 * Shared response DTOs mirroring the backend types (Avro `models/*.avdl`,
 * `model/*`, `schema/*`, graph and commentary records). Hand-maintained — keep
 * in sync with the Java records they mirror. Times sourced from `BigDecimal`
 * fields are serialized as numbers (picoseconds); Avro `local_timestamp_ms`
 * fields serialize as ISO strings.
 */
import {SqlImportStats} from './environment.service';
import type {ColumnRef, JoinPair, TableRef} from './refs';

export { SqlImportStats };
export type { ColumnRef, JoinPair, TableRef };

export type QueryActivityStatus = 'ACTIVE' | 'DORMANT' | 'UNKNOWN';

/*
 * `TableReference`, `ColumnReference` and `JoinColumns` used to be declared
 * here as objects. They are not: `model/TableReferenceSerde` and its siblings
 * serialize all three to strings, so the wire carries "schema.table",
 * "schema.table.column" and ["left", "right"]. The object shapes are gone
 * rather than merely unused — leaving them was what let call sites compile
 * against fields the server never sends. See refs.ts.
 */

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
  tables: TableRef[];
  type: string;
  joinColumns: JoinPair[];
  criteriaColumns: ColumnRef[];
  /**
   * Null, not empty, when the analyzer that fills these never ran or threw —
   * a skipped analysis, a parse failure, or an analyzer exception. The wire
   * really does send `null` here despite the Avro schema declaring `= []`,
   * and ~5% of profiles are in that state, so every read has to guard.
   */
  selectColumns: ColumnRef[] | null;
  groupByColumns: ColumnRef[] | null;
  generatedSource: string | null;
  truncated: boolean;
  analysisFailed: boolean;
  analysisSkipped: boolean;
  isDml: boolean;
  runtimeMetrics: RuntimeMetrics | null;
  digest: string;
  normalizedDigest: string;
  schemaName: string;
  schemas: string[] | null;
  mismatchedJoins: JoinPair[];
  lineNumber: number | null;
  analysisFailureReason: string | null;
  sourceFileName: string | null;
  sourcedFiles: string[];
  queryActivityStatus: QueryActivityStatus;
  modifiedForAnalysis: boolean;
  isMultiSchema: boolean;
  repeatedBlocks: RepeatedBlock[] | null;
  mergedChildren: MergedChild[] | null;
  /**
   * The MySQL performance-schema digests behind this profile — one per merged
   * member, deduped. Distinct from `digest`, which `complete()` overwrites with
   * the app's normalized digest.
   */
  perfDigests: string[] | null;
}

/** A block the normalizer collapsed: [start, end) in query text, seen count times. */
export interface RepeatedBlock {
  start: number;
  end: number;
  count: number;
}

/** A query folded into a profile because it normalized identically. */
export interface MergedChild {
  digest: string;
  repeatedBlocks: RepeatedBlock[] | null;
  executed: number;
  averageTime: number;
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
  from: ColumnRef[];
  to: ColumnRef[];
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
  underlyingTables: TableRef[] | null;
  /** Per-column lineage for views; null for base tables. */
  viewColumns: { name: string; computed: boolean; sources: ColumnRef[] }[] | null;
}

/** Per-table profile view (model/Table). */
export interface Table {
  name: string;
  numberOfQueries: number;
  totalQueryTime: number;
  queries: SqlProfile[];
  structure: TableStructure | null;
  schema: string;
  foreignKeyTables: TableRef[];
  joinTables: TableRef[];
  relatedTables: TableRef[];
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
  table: TableRef;
  foreignKeyTables: TableRef[];
  joinTables: TableRef[];
  relatedTables: TableRef[];
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
