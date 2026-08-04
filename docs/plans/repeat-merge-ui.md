# UI visualization of normalization merges and repeats

Follow-up to the normalizer work (repeat collapsing before hashing —
shipped; see git history of `normalizer.md`). WORK_TODO asks for: a link
directly to the performance-schema digest, and showing the repeat
difference for each merged child.

## Current state

- **Merge groups exist but are invisible.** When a query's normalized
  digest matches an earlier profile, `SqlRepositoryBuilder.attemptMerge`
  folds it in; the merged children's MySQL digests accumulate in
  `RuntimeMetrics.digestList` (`models/src/main/avro/SqlExplorer.avdl:60`,
  populated via `RuntimeMetrics` merging). The frontend type carries it
  (`ui/src/app/api-types.ts:55` `digestList: string[]`) but **nothing
  renders it** — a user can't see that a profile represents N merged
  performance-schema digests.
- **Repeat metadata is rendered** for the kept representative: the
  workbench detail shows "collapsed repeats" chips from
  `SqlProfile.repeatedBlocks` (offsets + ×count into the displayed
  normalized SQL). But the *per-child* repeat difference (child A had ×12,
  child B ×10 of the same block) is not stored — merging happens before the
  children's own repeat counts are kept (`attemptMerge` returns before
  normalization metadata for the child is recorded, and only the first
  profile's `repeatedBlocks` persist).
- **No performance-schema digest link.** The profile's own `digest` (MySQL
  statement digest) renders nowhere as a copyable/linkable value; digests
  are only used as route ids (`/analyzed-sql/:digest`).

## Proposed approach

1. **Capture per-child repeat counts at merge time (backend, M).** In
   `SqlEvidenceInputService.processRow`, compute `digestedQuery` *before*
   the merge attempt (already done); on a successful merge, record the
   child's digest + its repeats. Model: new Avro record
   `MergedChild { string digest; array<RepeatedBlock> repeatedBlocks; long executed; }`
   and `array<MergedChild> mergedChildren = []` on `SqlProfile`
   (locate offsets against the kept display text so ×counts are
   comparable). `digestList` stays for compatibility.
2. **Render the merge group (frontend, M).** In the workbench detail (and
   the full view), under the SQL: "merged N variants" expander listing each
   child digest (`mono`, copy-to-clipboard) with its repeat chips —
   highlighting where a child's ×count differs from the representative's.
3. **Performance-schema digest link (S).** Render the profile's `digest`
   as a copyable value with a "search performance_schema" affordance —
   simplest useful form: a copy button plus a canned SQL snippet
   (`select * from performance_schema.events_statements_summary_by_digest where digest = '…'`)
   surfaced in a tooltip/expander, since the app can't link into MySQL
   directly. If live-EXPLAIN-style opt-in server queries land later
   (explain-parser plan), this could become a real drill-through.

## Open questions

- Is "link directly to the performance schema digest" meant as the copyable
  digest + query snippet (proposed), or as a live server-side lookup of
  the digest row (needs the same opt-in decision as live EXPLAIN)?
- Are per-child *metrics* (executions, avg time) wanted alongside the
  repeat difference? `MergedChild.executed` is included in the proposal
  since the data is at hand during merge.
- Bumps the normalizer version story (new Avro fields, merge-time
  behavior) — coordinate with
  [normalization-versioning.md](normalization-versioning.md).

## Effort: M overall (backend capture + Avro + UI expander).
