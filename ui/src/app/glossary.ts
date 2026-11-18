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

  // ── column roles (sqlx-n5j.7, sqlx-n5j.8) ──
  orderByRole:
    'Queries that sort by the column (ORDER BY), whether named directly, through a select-list alias, or by '
    + 'position. Only the top level of each query is read, as for select and group by.',
  likeCriteria:
    'Queries whose criteria pattern-match on the column with LIKE or NOT LIKE. Still criteria: LIKE is a kind '
    + 'of criteria, not a role of its own. "%…" counts the queries whose pattern is known to start with a '
    + 'wildcard, which no index can serve. The pattern is usually a ? placeholder, so most are unknown, not safe.',
  // ── migration planning: write rate, lock risk, encodings (sqlx-n5j.5/.9/.10) ──
  writesPerDay:
    'INSERT, UPDATE, DELETE and REPLACE executions writing the table, per day. An AVERAGE: each statement\'s '
    + 'count divided by the days between its first and last seen (at least one day), summed. Performance '
    + 'Schema keeps no time-of-day breakdown, so a nightly batch reads as spread evenly over the day.',
  rowsPerDay:
    'Rows changed per day, averaged the same way. A statement is counted only against the tables it writes, '
    + 'so an INSERT … SELECT is not charged to the table it reads; but one that writes several tables reports '
    + 'rows for all of them. A statement that could not be parsed is counted against every table it names.',
  lockRisk:
    'A DDL statement (ALTER TABLE, CREATE INDEX, …) must take the table\'s metadata lock, so it waits for every '
    + 'statement already running on the table — and every query arriving after it queues behind the DDL. One '
    + 'slow statement in flight when the migration starts stalls all traffic to the table for as long as it runs.',
  lockMaxTime:
    'The slowest single execution in the captured period: how long a DDL could have to wait behind this '
    + 'statement. This is statement time only — a short statement inside a long open transaction holds its '
    + 'lock until the transaction ends, which Performance Schema\'s digest table cannot show.',
  longStatement:
    'A statement whose slowest execution reached the threshold. Runs per day for these is an upper bound on how '
    + 'often a slow execution is in flight, since most runs are nearer the average.',
  quietHours:
    'Not available: captured statement statistics are cumulative counts with a first and last seen, and carry no '
    + 'breakdown by hour, so they cannot say when a table is quiet.',
  predominantCollation:
    'Marked *: the collation most of the table\'s character columns use, shown because the declared default is '
    + 'not known: the table statistics were not read yet, or its shards disagree.',
  presumedKind:
    'A guess from the column\'s name and type alone; no data was read. identifier: keys, codes, hashes and emails, '
    + 'compared exactly and joined on, so a collation change alters what matches and which unique keys collide. '
    + 'enum: a small fixed vocabulary such as a status, ASCII in practice. text: what people typed, where '
    + 'non-ASCII and mis-encoded data lives. Inferred confidence only, until sampled or confirmed.',
  declaredCollation:
    'The table\'s declared default collation (information_schema.TABLES TABLE_COLLATION). A new column gets it '
    + 'unless it says otherwise, so a table whose columns all differ from it will keep adding columns that differ.',
  mixedEncoding:
    'The table\'s character columns use more than one character set or collation. Comparing or joining columns '
    + 'across them forces a conversion, and a converted column cannot use its index.',
  joinEncodingMismatch:
    'A join in the captured queries compares two character columns whose character set or collation differs. '
    + 'MySQL converts one side to compare them, so an index on that side cannot be used for the join; with two '
    + 'explicit collations it may refuse the comparison outright ("Illegal mix of collations").',
  // ── server roles and table sizes (sqlx-n5j.4, sqlx-n5j.2) ──
  serverRoles:
    'What config says each server is for. primary takes the application\'s writes; replica serves its reads; '
    + 'analytics is a replica the application does not read from. Only sampling grants anything: sql-explorer '
    + 'may run its own read-only, time-limited queries against table data there. None means none of these.',
  storageTotals:
    'Added up over the primaries: each shard holds its own rows, so shards sum. A server configured as a replica '
    + 'holds a copy of its primary\'s rows and is left out; a server with no roles configured counts. Every '
    + 'server\'s own figures are under "by server".',
  storageCaptured:
    'When these figures were read from the server, at its last schema load or pull. MySQL itself may have computed '
    + 'them earlier: it caches table statistics for information_schema_stats_expiry seconds (a day by default).',
  storageAvgRow:
    'Data bytes divided by estimated rows, over every primary together. Inherits the row estimate\'s error, and '
    + 'includes page overhead, so it is larger than the bytes a row\'s values take.',
  rowsEstimate:
    'information_schema.TABLES TABLE_ROWS, an estimate: for InnoDB it comes from sampled index statistics and '
    + 'can be far off, and MySQL caches it for up to a day. Never a COUNT(*). Summed over every shard that '
    + 'carries the table.',
  tableSize:
    'Data plus index bytes on disk (DATA_LENGTH + INDEX_LENGTH), as of when it was read, summed over every '
    + 'shard that carries the table. Allocated space, so a table that has had rows deleted can be larger than '
    + 'its data.',
  // ── annotations and generators (sqlx-n5j.13, sqlx-n5j.12) ──
  annotations:
    'Facts recorded about this object, kept apart from tags: what was stated, how sure, and who said so. '
    + 'Inferred by a rule, sampled from data, or confirmed by a person. A confirmation adds a row rather than '
    + 'overwriting one, so the history of how a fact came to be believed is kept.',
  generator:
    'Which ORM or library wrote the statement, from signatures in its SQL: table aliases like c0_ or this_, '
    + 'and the shapes of simple unit-of-work writes. Two generators can share a shape, so a match is only a '
    + 'candidate until someone confirms the environment uses that generator (Settings). The simple INSERT and '
    + 'UPDATE shapes match any code once Performance Schema has replaced the literals, so treat them as weak.',
  schemaRole:
    'What a schema is for, recorded as a fact rather than a tag. "system" marks MySQL\'s own schemas; '
    + 'excluding "system" in a scope hides them, the same as the system tag it replaced.',

  // ── the graph's ERD layout (sqlx-929) ──
  erdKind:
    'Whether an end of a relationship is one or many is inferred from the schema, not declared: an end is '
    + '"one" when a unique index is covered by the columns joined on that side, and "many" when none is. '
    + 'It says what the schema enforces, not what the data holds, so a column with no unique index reads '
    + 'as many even if it never repeats. For a join the columns are those of every query joining the two '
    + 'tables, so two queries each joining on half of a composite key read as unique between them.',

  // ── the graph's treemap layout (sqlx-7o0) ──
  treemapTime:
    'A tile\'s area is the total time of the queries touching the table, as Performance Schema timed them, '
    + 'and only the queries the search matches. A query is counted in full against every table it touches, '
    + 'so a join of three tables adds its time to all three and the tiles together overstate the time spent.',
  treemapDisk:
    'A tile\'s area is the table\'s data plus index bytes, as information_schema.TABLES reported them, '
    + 'summed over the servers that report it. It is the same whatever the search: the search only decides '
    + 'which tables are drawn. MySQL caches these figures (a day by default), and a view has none. '
    + 'Each tile is cut into the table\'s data (DATA_LENGTH: for InnoDB the clustered primary key, so the rows) '
    + 'and its secondary indexes (INDEX_LENGTH), hatched.',

  // ── comparing servers (sqlx-a9h.2) ──
  compareServers:
    'The queries the search matches, each set against its own figures on every server of one cluster. '
    + 'Per-day figures divide a server\'s totals by its own window, because each server\'s counters run from '
    + 'its own last reset. Rows with no flag are hidden unless "show all" is on.',
  compareDifference:
    'The time per day a row\'s flags are about: the largest of their costs. A read on the primary costs its '
    + 'seconds there; an uneven spread, the time above an even split; a plan difference, the time beyond what '
    + 'the leanest server\'s plan would take; and so on. A table\'s is its queries\' added.',
  flagOnPrimary:
    'A read that runs on the primary of a cluster that has replicas. A candidate to move, not a verdict: '
    + 'transactions are not visible, so it may be a read that has to see its own writes.',
  flagSkewed:
    'A read whose executions per day differ at least threefold between the cluster\'s replicas, a replica '
    + 'it never ran on counting as none. Sticky connections, a pinned job, or a balancer not spreading load.',
  flagSingleServer:
    'A read seen on only one of the cluster\'s non-primary servers, and not on the primary: a report or job '
    + 'pointed at one place. Expected on an analytics server; surprising on an application replica.',
  flagPlanDiffers:
    'Rows examined per execution differ tenfold or more between servers (at least 100 on the worse), counting '
    + 'only servers that ran it ten times or more. The same statement doing different work: different index '
    + 'statistics, an index missing on one server, or a different version.',
  flagLatencyDiffers:
    'Rows examined match within a factor of two, but time per execution differs threefold or more (at least '
    + '1 ms on the slower). The same work, done slower: a cold buffer pool, a smaller instance, or contention.',
  flagReplicaWrites:
    'Writes, or rows changed, on a server declared a replica or analytics. Under statement-based replication '
    + 'this is the replica applying the primary\'s writes; under row-based replication those do not appear, '
    + 'and something is writing to the replica directly.',
} as const;

export type GlossaryTerm = keyof typeof GLOSSARY;
