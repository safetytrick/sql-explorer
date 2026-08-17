# SqlProfile field derivation audit

Audited 2026-08-16, against the `regression` environment: 4,130 imported rows,
3,608 profiles after merging, dumped from `POST /queries/search`.

The question, per field: **where is it set, and can that code not run?** Four
bugs in one day shared a shape — a field set on a conditional path (an analyzer
that can be skipped or throw, `MergedSqlProfile`'s per-getter overrides,
`attemptMerge`'s per-field copy) sitting beside a consistency-linked partner
derived in the always-run `SqlProfileExtension.complete()`, with nothing making
the pair agree.

## The paths a profile can be built on

| Path | What it is |
|---|---|
| `complete()` | `SqlProfileExtension.Builder.complete/completeBuild`. Every profile passes through it. The right home for anything derivable from another field. |
| analyzer | One of the `SqlAnalyzer` implementations inside `SqlAnalysisService.analyzeSql`. **Does not run** for a skipped statement (`ignoredFirstTokens` / `ignoredSchemas`), a parse failure, a merged child, or an over-budget parse — and each analyzer's own exception is caught, so a later one can be skipped individually. |
| pre-scan | `SqlEvidenceInputService.processRow` before the branch: identity, metrics, type, and `RegexTableExtractor` tables on the skip/fail paths. Always runs. |
| `attemptMerge` | The per-field copy from the representative onto a merged child's builder (`SqlEvidenceInputService:421`). A hand-maintained list — what it omits keeps the builder default. |
| `MergedSqlProfile` | Per-getter overrides on a group. Constructed with `new`, not the Avro builder, so an un-overridden getter returns the uninitialised superclass field. |
| Avro default | The `= []` / `= null` in `SqlExplorer.avdl`. Applies only to a field the builder never touched *and* only if Avro materialises it — in practice unreliable, which is why `emptyUnsetLists` exists. |
| parquet reload | `ParquetSqlRepository` / `ParquetSink.readAll` reconstruct through `AvroParquetReader`, **bypassing `complete()` entirely**. A derivation added to `complete()` is invisible on a file written by older code until re-import. |

## The table

Verdicts: **(a)** genuine — can produce a wrong/null/inconsistent value on a
real profile shape; **(b)** latent — the split is real but unreachable in this
corpus; **(c)** fine as built.

| Field | Set by | Read by | Verdict |
|---|---|---|---|
| `id` | pre-scan (`source + "-" + rank`); `complete()` falls back to `digest`; `MergedSqlProfile` overrides with `digest` | `/analyzed-sql/{id}`, `ParquetSqlRepository.getById` (parquet `id` column filter) | **(a)** 3,418/3,608 carry the timestamped form, 190 the digest. Masked: the FE links by `digest` and `AnalyzedSqlRepository` indexes every digest. Not masked in the parquet repository, which nothing currently injects. → item |
| `query` | pre-scan (formatted raw, then normalized display if it parses); `MergedSqlProfile` → `first()` | everywhere | **(c)** |
| `tables` | analyzer (`TableNameAnalyzer`) **or** pre-scan (regex, on skip/fail); default schema applied in `complete()` | schema filters, schema tree, rollup, graph, table pages — the real schema key for the whole app | **(c)** both paths always set it |
| `type` | pre-scan (first token); copied by `attemptMerge` | type facets and filters | **(c)** |
| `joinColumns` | analyzer (unconditional setter); copied; emptied in `complete()` | graph, join panels, `constrainedBy` | **(c)** |
| `criteriaColumns` | analyzer (lazy add); copied; emptied in `complete()` | `constrainedBy`, column drill-down | **(c)** |
| `selectColumns` | analyzer (lazy add); emptied in `complete()`; **was not copied** by `attemptMerge` | `TableInsightsService`, SQL view | **(a) fixed** — see below |
| `groupByColumns` | as `selectColumns` | as `selectColumns` | **(a) fixed** |
| `generatedSource` | analyzer (`GeneratedSqlAnalyzer`); **was not copied** by `attemptMerge` | FE type only, never rendered | **(a) fixed** (0 non-null in this corpus — no `this_` aliases) |
| `truncated` | pre-scan and `SqlAnalysisService`; copied | search flag, import stats | **(c)** |
| `analysisFailed` | pre-scan; derived in `complete()` from the reason; copied | search filter, reports, SQL view banner | **(c)** invariant already asserted |
| `analysisSkipped` | pre-scan only | search filter, import stats | **(c)** a skipped statement never reaches `attemptMerge` (it sets no normalized digest) |
| `isDml` | pre-scan; copied | search filter, stats | **(c)** |
| `runtimeMetrics` | pre-scan; `MergedSqlProfile` sums the group | every metric, sort, and Pain number | **(a) fixed** — the sum dereferenced every element of a nullable-by-schema field. Zero-`executed` division: **(b)**, 0 profiles in corpus |
| `digest` | `complete()` (overwritten with the normalized digest) | merging, tagging, every FE link | **(c)** |
| `normalizedDigest` | `complete()` | grouping key | **(c)** all 3,608 equal `digest` |
| `schemaName` | pre-scan (session schema, or `"dual"`); **overwritten** by `SchemaAnalyzer`, and now by `complete()`, with the sole schema | `AnalyzedSqlRepository:60`, FE link inputs | **(a) fixed** — 133/3,608 named a schema absent from their own `schemas`: 127 skipped single-schema profiles (now 0) and 6 analysed multi-schema ones (left as-is by design) |
| `schemas` | `complete()`, from `tables`, after the default-schema pass | `isMultiSchema` derivation; FE declares it | **(c)** fixed earlier today (was 1,816/3,608 incomplete) |
| `mismatchedJoins` | analyzer (unconditional setter); copied; emptied in `complete()` | reports, FE badge | **(c)** |
| `lineNumber` | pre-scan; `MergedSqlProfile` takes the group minimum | FE type only, never rendered | **(a) fixed** (NPE) / **(b)** for the value: null on a single profile, `Integer.MAX_VALUE` on a merged one → item |
| `analysisFailureReason` | analyzer catch in `SqlAnalysisService`, **last writer wins**; pre-scan on parse failure; copied | SQL view banner, `ANALYZER_FAILURE_PREFIX` classification, two reports | **(a)** one field, N conditional writers, no accumulation: two failing analyzers and only the second is reported. → reshapes item #19 |
| `sourceFileName` | pre-scan (TSV sources only); `MergedSqlProfile` → `first()` | FE type only, never rendered | **(c)** 0 non-null here (the active source is not a TSV) |
| `sourcedFiles` | **nothing writes it**; `emptyUnsetLists` gives it `[]`; `MergedSqlProfile` flat-maps empties | FE type only, never rendered | **(a)** dead field — 0/3,608 non-empty → item |
| `queryActivityStatus` | pre-scan; `MergedSqlProfile` takes the most active | sort comparator, search filter | **(c)** |
| `modifiedForAnalysis` | `SqlAnalysisService` only; **was not copied** by `attemptMerge`; `MergedSqlProfile` unions | FE type only, never rendered | **(a) fixed** (1,031/3,608 true) |
| `isMultiSchema` | `SchemaAnalyzer` **and** derived in `complete()` from `schemas` (promotion only) | search flag, `SqlProfileReportService` | **(c)** fixed earlier today |
| `repeatedBlocks` | pre-scan (per member, deliberately); `MergedSqlProfile` → `first()` | the ×N badges | **(c)** documented as intentional |
| `mergedChildren` | `recordMergedChild`, on the representative only; `MergedSqlProfile` → `first()` | the variants expander | **(b)** correct as long as `first()` is the representative — which `index()` does not guarantee → item |

Two derived getters, for completeness: `perfDigests` (from `runtimeMetrics`,
deduped across the group) and `allIds` — both **(c)**.

## What the corpus says

All against the 3,608 live profiles, after today's earlier fixes were deployed
(confirmed by re-running the coverage check that used to fail on 1,816 of them,
which now returns 0):

```
schemas empty while tables non-empty ........  0     (was 1,816 this morning)
schemas missing a table's schema ............  0
isMultiSchema disagreeing with schemas ......  0     both directions
null list fields ............................  0
schemaName not in schemas ................... 133    127 skipped + 6 analysed
  ├ single schema, named another ............ 127    all analysisSkipped
  └ multi-schema, named none of them ........   6    all analysed, isMultiSchema
mergedChildren total ........................ 206    == the import's merged=206
profiles owning a merge group ...............  71
unqualified tables ..........................  0
sourcedFiles non-empty ......................  0
sourceFileName non-null .....................  0
generatedSource non-null ....................  0
lineNumber null .............................  0
runtimeMetrics null / executed==0 ........... 0 / 0
analysisFailureReason without the flag ......  0
```

`mergedChildren` totalling exactly the import's `merged=206` is the
discriminating one: it means every group kept its representative in `first()`,
so none of the `attemptMerge` omissions were being read.

## Not fixed, and why

Everything above marked "→ item" is in `WORK_TODO.md` with these numbers
attached. The line between fixed and flagged: a fix went in when it filled a
value that was missing or made a getter answer instead of throw, and stopped
when it would have changed the concurrency (`index()` ordering) or picked
between two defensible aggregation rules (`MergedSqlProfile`'s
union-vs-delegate mix).

`schemaName` was the one case flagged for meaning rather than mechanism, and
it came back approved: the field now means *the* schema on every path, not
"the session it ran in" on some of them. `complete()` collapses it onto the
sole entry of `schemas`, guarded to the single-schema case so it computes the
identical value `SchemaAnalyzer` does and cannot contradict it. The six
analysed multi-schema profiles keep the session value — a statement spanning
schemas has no single answer, which is what `isMultiSchema` is for.
