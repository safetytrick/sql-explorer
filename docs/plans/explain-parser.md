# Improve the explain plan parser

> **Status: (a)+(b) implemented 2026-08-02.** The `tsv` dependency is gone
> (removed from package.json; `npm ci` prunes it). A hand-rolled
> `explain-parser.ts` sniffs and parses four paste shapes: TSV, the mysql CLI
> grid (`|` pipes + `+---+` borders), `\G` vertical blocks, and
> `EXPLAIN FORMAT=JSON` (access nodes flattened from the query_block tree,
> with filesort/temporary/index/where mapped into Extra). Typed rows
> (`NULL`→null, numeric rows/filtered), explicit error states instead of
> silent zero rows, the previously-dropped `partitions` column, an
> `est. rows` column (rows × filtered%), full-scan row highlighting +
> ALL/no-key badges, filesort/temporary badges, and table names linking to
> the table lens when the name is unique in the loaded schema tree. The
> component was restyled from Material to the lens `data-table` vocabulary
> and no longer re-parses per keystroke via setter side effects. Real specs:
> `explain-parser.spec.ts` (5 scenarios, also executed under node during
> development) + component annotation spec.

## Decision needed: live EXPLAIN execution (option c)

Running `EXPLAIN <query>` against the environment's servers from the query
detail view is the feature-level win — it removes the paste step entirely
and enables explain-over-time. It is **not implemented**; it is a
product/security call because it means the app *executes statements* on
analyzed servers, where today it only reads metadata and
performance_schema.

Recommendation: implement it, but gated — (1) a per-environment opt-in
config flag (default off), (2) `EXPLAIN`-prefixed statements only, built
server-side from the stored digest text (never client-supplied SQL), (3) a
short statement timeout and read-only connection expectations documented in
DATA_ENVIRONMENTS.md. The parsing/rendering layer built here is
format-compatible with what a `FORMAT=JSON` endpoint would return, so (c)
plugs into the same table.

## Current state

The "parser" is 46 lines of frontend: `ExplainViewComponent`
(`ui/src/app/explain-view/explain-view.component.ts`) parses **pasted TSV** with
the unmaintained `tsv` npm package into `Record<string, string>` rows and shows
a hardcoded `mat-table` column list. There is no backend explain code at all —
no fetching, no persistence, no `EXPLAIN` execution (the environment layer only
introspects schema; incoming statements that *are* EXPLAINs get skipped,
`SqlEvidenceInputService.java:81`).

Specific gaps: tabular-TSV-only (no `FORMAT=JSON`/`TREE`/`ANALYZE`, no `\G`);
`partitions` column defined but never displayed (`ts:15-16` vs `html:18-21`);
no validation (bad paste silently yields zero rows); no semantics (no
`type=ALL` warning, no `rows×filtered` estimate, no links to `/table/:s/:n`);
disconnected from the workbench; re-parses on every keystroke.

## Interpretation / options

"Improve the parser" ranges from (a) hardening the paste-TSV path, through
(b) supporting `EXPLAIN FORMAT=JSON` paste with a tree rendering, to (c) a
backend endpoint that runs `EXPLAIN` against the environment's servers for a
selected query. (c) is the feature-level win but is a new capability (running
statements on analyzed servers — permission implications), not parser work.

## Proposed approach

Do (a)+(b) frontend-only first: replace the `tsv` dependency with a small
hand-rolled parser handling TSV, `\G` vertical format, and JSON; detect format
by sniffing; add typed row model, error state, the missing `partitions` column,
and semantic annotations (full-scan highlight, missing-key warning, row
estimates, table names linked to the table lens). Add real specs (the current
spec is a CLI stub). Consider (c) as a follow-up item with its own plan.

## Open questions

- Is running `EXPLAIN` against live servers desirable/safe in your deployments
  (read-only credentials?), or should this stay paste-driven?
- Which format do you actually paste most — CLI grid, TSV, or JSON?

## Effort: S–M for (a)+(b); M–L for (c).
