# Create a test framework for the parser that accepts the schema as SQL

> **Status: steps 1–2 implemented 2026-08-02.**
> Step 1: `fromCreateTable` now mirrors the JDBC path — JDBCType mapping,
> NOT NULL/AUTO_INCREMENT/DEFAULT/GENERATED column specs (JDBC-style
> "YES"/"NO" strings, unquoted defaults, NULL default = null), ordinals,
> schema/table on each column, unique/PRIMARY index handling, and
> `NameNormalizer` on table/schema/index-column names (CREATE VIEW names
> too).
> Step 2: `SchemaFixture` (test tree, analysis package) —
> `fromSql("create table …; …")` / `fromResources(names…)` / `allFixtures()`
> → `resolver()`, `service(analyzers…)`, and `analyze(sql)` running the full
> analyzer set and returning a completed `SqlProfile`. The five analyzer test
> classes now set up through it; `SchemaFixtureTest` documents the pattern.
> Choices: step 3 (data-driven corpus harness) deferred — ergonomics first,
> revisit when a batch of new parser cases arrives; `ColumnTableResolverTest`
> kept on inline `fromCreateTable` builders since it deliberately tests
> multi-schema resolution with per-table schema overrides;
> `TestSchemas.all()`'s filesystem enumeration left as is (tests always run
> exploded; noted, not worth the churn).
> Found & fixed along the way: the fixture-vocabulary scrub had over-renamed
> the Avro accessor `getCriteriaColumns()` to `getRuleColumns()` in two test
> classes (50 occurrences restored).

## Current state

This exists in embryonic form and the item is best read as "grow and harden it":

- `TableStructure.fromCreateTable(String ddl[, String defaultSchema])`
  (`schema/TableStructure.java:81-145`) parses `CREATE TABLE` via jsqlparser and
  is used **only by tests**. Known gaps flagged in-code (`:108`, `:130`): index
  type/direction/uniqueness and column nullability/defaults/auto-increment/
  JDBCType/collation are unset; `type` is never set; table/schema names skip
  `NameNormalizer` (index and column names don't).
- `TestSchemas` (`src/test/java/.../analysis/TestSchemas.java`) loads 21
  `CREATE TABLE` fixtures from `src/test/resources/schema/*.sql` into
  `LoadedSchema` (`of(names…)`, `all()`, default schema `tdb`). `all()`
  enumerates via filesystem listing — breaks if tests ever run from a jar.
- Per-test wiring is copy-pasted: `new ColumnTableResolver(TestSchemas.all())`
  plus `new SqlAnalysisService(analyzers…)` in five analyzer test classes
  (`SqlAnalysisServiceTest.java:28-38` is representative);
  `ColumnTableResolverTest` uses inline DDL heredocs instead. Assertion helpers
  live in `analysis/Assertions.java`. No Testcontainers/H2 anywhere
  (`build.gradle:118-120`).

## Proposed approach

1. **Fill the `fromCreateTable` gaps (S–M):** nullability, defaults,
   auto-increment, JDBCType mapping, unique indexes, `NameNormalizer` on
   table/schema names, and `CREATE VIEW` support (ties into
   [views-vs-tables.md](views-vs-tables.md)). Extend `TableStructureTest`.
2. **A fluent test fixture (S):** `SchemaFixture.fromSql("create table …;
   create table …")` — multi-statement string or resource path → `LoadedSchema`;
   plus an `analyze(sql)` helper that wires
   `ColumnTableResolver` + chosen analyzers and returns the `SqlProfile` for
   assertion. Collapses the copy-pasted `@BeforeEach` blocks.
3. **Table-driven cases (M, optional):** pair fixture files — schema SQL +
   query SQL + expected JSON/YAML of extracted tables/joins/columns — executed
   by a single parameterized JUnit test, so new parser cases are data, not code.
   The existing `slow-parse-*.sql` fixtures are natural first entries.

## Open questions

- Is the goal ergonomics (steps 1–2) or a corpus-style regression harness
  (step 3)? Step 3 is what makes "accepts the schema as sql" a *framework*.
- Should fixtures move to real-world-shaped but anonymized DDL (ties into
  [secret-scrub.md](secret-scrub.md) — the current fixtures are only partially
  anonymized)?

## Effort: S–M for steps 1–2; +M for step 3.
