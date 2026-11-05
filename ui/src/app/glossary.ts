/**
 * What the app's numbers and concepts mean, written once.
 *
 * Shown as hover text on a `.term` (dotted underline, help cursor) wherever
 * the concept appears, so the same column header explains itself the same
 * way on every page (sqlx-c8w.5, sqlx-tcv.1). The people using this tool —
 * its author included — regularly have to re-learn what a number counts;
 * the definition belongs next to the number rather than in their memory.
 *
 * Keep entries to what the number *is*, including its caveats. A definition
 * that flatters the number is worse than none.
 */
export const GLOSSARY = {
  tableQueries:
    'Distinct query patterns that touch this table. Statements that differ only in their literal values '
    + 'are normalized into one pattern, so a query run a million times with different ids counts once.',
  tableTime:
    'Time spent executing those patterns: each pattern\'s total latency across the captured period, summed.',
  schemaQueries:
    'The table counts of this schema added up. A pattern touching several of its tables is counted once per '
    + 'table, so this can exceed the number of distinct patterns.',
  schemaTime:
    'The table times of this schema added up. A pattern touching several of its tables contributes its '
    + 'time once per table, so this overstates time spent; use it to rank schemas, not as a measured total.',

  // ── the workbench's Analyzer filters (sqlx-ywy.4) ──
  analyzerWarnings:
    'The analyzer found a join between columns of different types (a "mismatched join"). MySQL converts one '
    + 'side to compare them, which can stop an index on that column being used.',
  analysisFailed:
    'sql-explorer could not analyze the statement: either the parser could not read it, or it parsed and an '
    + 'analyzer then failed. Its tables and columns may be missing or come from a pattern match instead.',
  cleanAnalysis:
    'Parsed and analyzed with no failure and no analyzer warnings.',
  analysisSkipped:
    'Never parsed, on purpose: statements whose first word is on the ignore list (SHOW, SET and the like), or '
    + 'that touch only ignored schemas. Any tables listed come from a pattern match, and SHOW lists none.',
  analyzerFailure:
    'Parsed, but an analyzer failed on it. Usually a bug in sql-explorer worth reporting. Truncated captures '
    + 'are not counted here.',
  parseFailure:
    'The SQL parser could not read the statement: syntax it does not support. Truncated captures are not '
    + 'counted here, since a cut-off statement cannot parse.',

  // ── runtime metrics, from Performance Schema's digest summary (sqlx-tcv.1) ──
  executed: 'How many times the pattern ran in the captured period.',
  errors: 'Errors MySQL raised across all executions (Performance Schema counts errors, not failed executions).',
  warnings: 'Warnings MySQL raised across all executions (one execution can raise several).',
  totalTime: 'Total execution time across all executions. What "impact" ranks by.',
  maxTime: 'The slowest single execution in the captured period.',
  averageTime: 'Total time divided by executions.',
  lockTime: 'Time spent waiting for table locks, included in the execution time.',
  rowsSent: 'Rows returned to the client.',
  rowsScanned:
    'Rows the server read to produce the result. Far more scanned than sent means rows were read and thrown '
    + 'away, which is often an index opportunity.',
  rowsAffected: 'Rows changed by INSERT, UPDATE or DELETE.',
  tempTables:
    'Internal temporary tables MySQL created to run the statement, typically for GROUP BY, DISTINCT, UNION '
    + 'or a derived table.',
  tempDiskTables:
    'Temporary tables that went to disk instead of memory: too large, or holding column types the memory '
    + 'engine cannot store. Much slower than in-memory ones.',
  rowsSorted: 'Rows sorted for ORDER BY or GROUP BY across all executions.',
  sortMergePasses:
    'Sort spill. A sort that does not fit in sort_buffer_size is written to disk and merged back in passes; '
    + 'any value above zero means sorts spilled to disk.',
  firstSeen: 'When Performance Schema first recorded the pattern, as of the capture.',
  lastSeen: 'When Performance Schema last recorded the pattern, as of the capture.',
  activity:
    'Active: seen within 30 days of the capture and run more than once. Dormant: anything else. A dormant '
    + 'pattern may simply have run once, or not since a deploy.',

  // ── the rest of the workbench filters ──
  selectivity:
    'Rows sent divided by rows scanned, as a percentage. Low means most of what was read was thrown away. '
    + 'The interesting range is near zero: 0.01% and 0.1% are very different queries.',
  fullScan:
    'At least one execution used no index, or no good index, to find its rows (Performance Schema\'s '
    + 'no-index and no-good-index counters).',
  truncated:
    'Performance Schema cut the statement text off at its digest length limit, so the end of the query is '
    + 'missing and it cannot be fully analyzed.',
  multiSchema: 'The statement touches tables in more than one schema.',
  indexHint: 'The statement carries a USE, FORCE or IGNORE INDEX hint on one of its tables.',
  hasExamples:
    'A real statement, with its literal values, is stored for the pattern. Only those can be EXPLAINed: the '
    + 'pattern itself has ? placeholders.',
  impact: 'Total execution time across all executions: what costs the server the most overall.',
  tableTags: 'Queries that touch at least one table carrying any of these tags. Tagged with, above, '
    + 'matches tags on the query itself; this matches tags on its tables.',
} as const;

export type GlossaryTerm = keyof typeof GLOSSARY;
