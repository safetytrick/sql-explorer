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
} as const;

export type GlossaryTerm = keyof typeof GLOSSARY;
