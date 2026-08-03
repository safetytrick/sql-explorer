# Distinguish between views and tables

> **Status: steps 1–2 implemented 2026-08-02.**
> Step 1: view flag on `SchemaTree.TableNode` + `TableRollup`, `type` set by
> `fromCreateTable` incl. `CREATE VIEW`, badges + all/tables/views filters in
> sidebar, tables list, and table lens.
> Step 2: `ViewDefinitionService` bulk-loads `information_schema.VIEWS` at
> schema-load time (not lazy SHOW CREATE VIEW — one query, no per-table churn)
> and parses definitions with jsqlparser's `TablesNamesFinder`;
> `TableStructure.underlyingTables` carries the result (null for base tables),
> `fromCreateTable` extracts the same from `CREATE VIEW` DDL, and the table
> lens header shows "over schema.table, …" links for views.
> Column lineage (2026-08-02): `ViewLineageResolver` (analysis/resolver)
> resolves each view output column to base-table columns using the analyzers'
> own machinery (`SchemaAwareTableAliasResolver` + `ColumnTableResolver`);
> results live in `TableStructure.viewColumns` (`ViewColumn{name, sources,
> computed}`) and render as a "source" column in the table-lens Columns card
> (links per source, ƒ badge for expressions, ? for unresolved).
> Degradation rules: only plain SELECTs are modelled — UNION views keep
> table-level lineage with no column lineage; ambiguous columns (present in
> several joined tables) and unknown tables keep their name with empty
> sources (rendered "?"); expressions are computed=true with every resolvable
> column inside as a source (literal-only expressions have none); stars
> expand against the schema's column lists, a star over an unknown alias
> expands to nothing; views over views resolve one level (production views
> carry real JDBC column lists, so one level usually lands on the inner
> view's columns, not the base tables).
> Other choices: view rollup metrics stay separate (no base-table
> attribution); analysis-time view expansion still deferred; unreadable or
> unparseable definitions degrade to an empty list rather than failing schema
> load; the graph is untouched (view→base edges would need a GraphV2 payload
> change — revisit with graph-generalization). Step 3 (sidebar attribute
> filtering beyond view/table) remains.

## Current state

The backend already captures the distinction and then drops it on the floor:

- `SchemaRepositoryFactory.load()` calls `getMetaData().getTables(null, null, null, null)`
  with `types = null`, so views are already introspected
  (`schema/SchemaRepositoryFactory.java:72`), and `TABLE_TYPE` is stored:
  `.type(tables.getString("TABLE_TYPE"))` (`:101,108`).
- `TableStructure.type()` (`schema/TableStructure.java:17`) has zero consumers in
  the backend. It is serialized by `GET /metadata/table/{schema}/{name}` and even
  declared in `ui/src/app/api-types.ts:156` — but no UI reads it, and
  `SchemaLensService.getTableMeta` types the response as `{ indices }` only
  (`ui/src/app/schema-lens.service.ts:138-140`).
- `TableStructure.fromCreateTable(...)` (the test/DDL path) never sets `type`.
- Nothing reads view definitions today (no `VIEW_DEFINITION`,
  `SHOW CREATE VIEW`, or `information_schema.VIEWS` anywhere). Closest reusable
  machinery: `CreateStatementService` (`schema/CreateStatementService.java:41`)
  happens to return CREATE VIEW DDL for views (it takes column 2 of
  `show create table`), and `TableNameAnalyzer` / `ExtendedTableNamesFinder`
  already extract table references from a parsed statement. jsqlparser 5.3 has
  `CreateView` support.
- The `/tables` list (`TableNamesComponent` → `GET /tables` →
  `SqlRepositoryService.getAllTablesWithRollup()`, `evidence/SqlRepositoryService.java:215-231`)
  is built from *analyzed queries*, not schema metadata — `TableRollup`
  (`model/TableRollup.java:9-13`) has no type field and the list UI
  (`table-list-component/`) has no filtering at all today.
- The schemas sidebar (`SchemaTreeComponent` → `GET /schema-tree` →
  `SchemaTreeService.getTree()`, `profiles/SchemaTreeService.java:43-86`) merges
  schema metadata at `:65-72` (only `columns().size()` is taken today) — the
  natural place to thread a view flag onto `TableNode`. The sidebar already has
  client-side text + tag filtering (`schema-tree.component.ts:113-127`).

## Proposed approach

Three increments matching the TODO's own numbering:

1. **Surface the flag (S).** Add `isView` (or pass `type` through) to
   `SchemaTreeService.TableNode` and to `TableRollup` (via a
   `SqlSchemaService.getTable()` lookup per row, or precomputed map per request).
   Render a small `view` badge in `TableListComponent`, `SchemaTreeComponent`,
   and the `TableLensComponent` header (`table-lens.component.html:1-8`), and add
   a `tables/views/all` toggle to the tables list and sidebar filter panel.
   Also set `type` in `TableStructure.fromCreateTable` when given `CREATE VIEW`.
2. **Parse view definitions (M).** New `ViewDefinitionService`: for tables with
   `type == "VIEW"`, load DDL via `CreateStatementService` (or
   `information_schema.VIEWS.VIEW_DEFINITION`), parse with
   `SqlParser`/`ExtendedTableNamesFinder` to get underlying tables. Store on
   `TableStructure` (new field `List<TableReference> underlyingTables`) or a
   side map in `LoadedSchema`. Expose on the metadata endpoint and show in the
   table lens ("view over: a, b, c"). Optionally: resolve queries against a view
   down to base tables during analysis — bigger, defer.
3. **Attribute filtering in sidebar (S–M).** Generalize the sidebar filter panel
   to table attributes (view/table now; warn flag, column count later) —
   client-side is enough since `SchemaTree` already ships the data.

## Open questions

- Should Pain/rollup metrics for a view be attributed to its underlying tables
  (double counting?) or kept separate?
- Is analysis-time view expansion (resolving view columns to base-table columns
  in `ColumnTableResolver`) in scope, or just display?

## Effort: S for step 1; M overall.
