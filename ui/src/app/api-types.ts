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
   * The `| null` is defence, not a description of the wire.
   *
   * <p>This used to read "the wire really does send null here … ~5% of
   * profiles", which was true when it was written and is not now: `complete()`
   * normalises these and `MergedSqlProfile` overrides them, so the backend
   * sends `[]`. Checked against the live corpus — 1,000 profiles, zero nulls
   * in any of `selectColumns`, `groupByColumns`, `schemas`, `tables`,
   * `criteriaColumns`, `joinColumns`, `mismatchedJoins` or `sourcedFiles`.
   *
   * <p>The types are kept nullable anyway. Nothing in the backend *enforces*
   * the normalisation — it is two call sites agreeing — so a guard costs
   * nothing and a missing one would be a runtime error on a page. What is
   * corrected here is the claim about how often it happens, because a comment
   * that says "~5%" teaches a reader to expect something the backend no longer
   * does.
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
  /** Nullable for the same reason as `selectColumns` above: defence, not the wire. */
  schemas: string[] | null;
  mismatchedJoins: JoinPair[];
  lineNumber: number | null;
  analysisFailureReason: string | null;
  sourceFileName: string | null;
  sourcedFiles: string[];
  /** The sources it was captured from, by source ref (a live source is its server's name). */
  sources: string[] | null;
  /** The tables it writes ("schema.table"), apart from those it only reads; empty when unknown (sqlx-n5j.16). */
  writeTables: string[] | null;
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
  /**
   * Indexes the statement names in index hints, one per name (sqlx-yj0).
   * Nullable like `selectColumns`: defence, not the wire — and absent from a
   * backend older than the field.
   */
  indexHints?: IndexHint[] | null;
  /** Columns the statement sorts by (sqlx-n5j.8). Optional like `indexHints`. */
  orderByColumns?: ColumnRef[] | null;
  /**
   * How the criteria columns are compared, LIKE included (sqlx-n5j.7): a
   * qualifier on `criteriaColumns`, not a role. Optional like `indexHints`.
   */
  criteriaComparisons?: CriteriaComparison[] | null;
  /**
   * Generator signatures the statement matched, one per rule (sqlx-n5j.12).
   * Candidates only: see `generator-origin.ts` for when one is the origin.
   */
  generatorCandidates?: GeneratorCandidate[] | null;
}

/** One way a WHERE clause compares one criteria column. */
export interface CriteriaComparison {
  column: ColumnRef;
  /** equality, inequality, range, in, not in, like, not like, null check. */
  kind: string;
  /** like / not like only: null when the pattern could not be read (a `?`). */
  leadingWildcard: boolean | null;
}

/** One generator signature a statement matched. */
export interface GeneratorCandidate {
  generator: string;
  rule: string;
}

/** One index named by a MySQL index hint on one table. */
export interface IndexHint {
  table: TableRef;
  /** As the statement wrote it. */
  index: string;
  /** Unquoted and lower-cased, as MySQL compares index names. */
  normalizedIndex: string;
  action: 'USE' | 'FORCE' | 'IGNORE';
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
  /** INSERT/UPDATE/DELETE/REPLACE executions per day, averaged over each statement's window (sqlx-n5j.10). */
  writesPerDay?: number;
  /** information_schema TABLE_ROWS — an estimate — summed over shards; null when unknown (sqlx-n5j.2). */
  rowsEstimate?: number | null;
  /** DATA_LENGTH + INDEX_LENGTH bytes, summed over shards; null when unknown. */
  sizeBytes?: number | null;
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
  /** One entry per server (shard) reporting the table; null when none was read (sqlx-n5j.2). */
  stats?: TableStorageStats[] | null;
}

/** One server's information_schema.TABLES figures for a table, as of readAt (sqlx-n5j.2). */
export interface TableStorageStats {
  server: string;
  readAt: string;
  /** TABLE_ROWS: an estimate, never a count. */
  rowsEstimate: number | null;
  dataLength: number | null;
  indexLength: number | null;
  dataFree: number | null;
  avgRowLength: number | null;
  autoIncrement: number | null;
  rowFormat: string | null;
  engine: string | null;
  createTime: string | null;
  updateTime: string | null;
  tableCollation: string | null;
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

/**
 * A stored example: one real statement, parameters intact, for a normalized
 * query group.
 *
 * `normalizedDigest` is **derived by the server** from the submitted text — it
 * is never sent. So the digest that comes back may not be the digest of the
 * page the example was submitted from, and that is the feature working: the
 * paste did not belong to this group. See `matchedAProfile`.
 */
export interface QueryExample {
  id: string;
  queryText: string;
  normalizedDigest: string;
  sessionSchema: string;
  /** Whether the schema was guessed from the statement rather than stated. */
  schemaInferred: boolean;
  description: string | null;
  environmentName: string | null;
  createdOn: string;
  /**
   * Whether a loaded profile carries the derived digest. Null on a read, where
   * the server did not evaluate it. False is not an error — a statement can age
   * out of the live window — but it is also what a wrong parameterization looks
   * like, so it is surfaced rather than swallowed.
   */
  matchedAProfile: boolean | null;
}

/** The body of a 4xx from the examples endpoint. */
export interface QueryExampleRejected {
  reason: string;
  detail: string;
  /** Populated for AMBIGUOUS_SCHEMA: the schemas the statement could have meant. */
  candidateSchemas: string[];
}

/** A plan from the live EXPLAIN endpoint. */
export interface ExplainResult {
  /** MySQL's `FORMAT=JSON` document, which the explain parser already reads. */
  plan: string;
  serverName: string;
  exampleId: string;
}

/**
 * How much of an environment sql-explorer actually saw.
 *
 * <p>Read this before concluding anything from an absence: "no query touches
 * this table" is a claim about the capture as much as about the table. Every
 * finding pairs its measurement with the sentence saying what to conclude, so
 * a view renders it without knowing any MySQL internals — and the words cannot
 * drift away from the number (sqlx-n01).
 */
export type CoverageVerdict = 'OK' | 'IMPAIRED' | 'UNKNOWN';

export type CoverageSignal =
  'DIGEST_CAPACITY' | 'DIGEST_TRUNCATION' | 'OBSERVATION_WINDOW' | 'PROVENANCE' | 'SAMPLING_GAPS'
  | 'ARCHIVED_HISTORY' | 'UNREACHABLE' | 'PENDING_ANALYSIS';

export interface CoverageFinding {
  signal: CoverageSignal;
  /** UNKNOWN is not a pass — it means the question could not be answered. */
  verdict: CoverageVerdict;
  measurement: string;
  /** What the measurement was judged against, or why it could not be. */
  basis: string;
  explanation: string;
}

/** Zone-less, because that is what Performance Schema reports. */
export interface ObservedWindow {
  from: string;
  to: string;
  days: number;
}

export interface SourceCoverage {
  sourceRef: string | null;
  /** How the rows reached us: PERFORMANCE_SCHEMA, CSV_EXPORT or PARQUET_COPY. */
  origin: string | null;
  capturedAt: string | null;
  profiles: number;
  window: ObservedWindow | null;
  findings: CoverageFinding[];
}

export interface CoverageReport {
  environment: string;
  loaded: boolean;
  loadedOn: string | null;
  originTable: string;
  /** Never merged: two sources may have read different servers. */
  sources: SourceCoverage[];
  /** Where each cluster's schema was read from. A source belongs to the cluster listing its server. */
  clusters: ClusterSchemaStatus[];
  /** Tables that clusters carrying the same schema define differently (sqlx-sqy.7). */
  drift: TableDrift[];
}

/** One table clusters disagree on; variants in cluster order, lookups using the first present one. */
export interface TableDrift {
  table: string;
  variants: {clusters: string[]; present: boolean}[];
}

/** A primary and its replicas, and which of them supplied the schema (sqlx-sqy.2). */
export interface ClusterSchemaStatus {
  cluster: string;
  /** Every server in the cluster, each also a source's sourceRef. */
  servers: string[];
  /** The server that supplied the schema; null when none could. */
  schemaServer: string | null;
  /** True when that server was not the cluster's first choice. */
  fallback: boolean;
  /** Servers tried before it, as "name: why". */
  passedOver: string[];
  /** Why no server could supply it; null when one did. */
  failure: string | null;
  /** Whether a server confirmed this is the schema it has now. False for a snapshot no reachable server vouched for. */
  current: boolean;
  /** Whether it came from the cluster's persisted snapshot rather than a full read. */
  fromSnapshot: boolean;
  /** When the schema was read from a server; null when it never was. */
  readAt: string | null;
}

/**
 * One source a statement ran on, with its figures there alone (sqlx-rw2.9):
 * read from the corpus scoped to that source, as `/queries?sources=` is.
 */
export interface QuerySource {
  /** The source ref; for a live source, the server's name. */
  source: string;
  /** The configured cluster, or null when no configured server has this name (an imported file, say). */
  cluster: string | null;
  roles: string[];
  capturedAt: string | null;
  executions: number;
  seconds: number;
  averageMillis: number;
  rowsScanned: number;
  rowsSent: number;
  rowsAffected: number;
  firstSeen: string | null;
  lastSeen: string | null;
  /** This source's part of the statement's time across every source, 0..1. */
  shareOfTime: number;
}
