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
} as const;

export type GlossaryTerm = keyof typeof GLOSSARY;
