# UI visualization of normalization merges and repeats

> **Status: implemented 2026-08-07.** Michael's calls on the two open
> decisions: **persist** the group (Avro field, durable through parquet,
> not in-memory), and a **self-contained copy button** for the digest —
> the legacy `grafana.local` / `rundeck.local` machinery in
> `SqlGraphService` stays untouched and no per-environment URL config was
> added. Shipped as `MergedChild` + `SqlProfile.mergedChildren`,
> merge-time capture in `SqlEvidenceInputService`, and a "merged N
> variants" expander plus copy buttons in the workbench detail.

## What the original plan got wrong

This section is kept because the plan's *Current state* misdescribed the
code, and the corrections are what shaped the implementation.

- **"Merged children aren't stored" — they were.** `attemptMerge` builds
  the child profile and calls `addToIndex`, so every child was already in
  `digestAnalyzedSqlRepository` under the shared normalized digest, and
  `MergedSqlProfile` already wrapped the group as `sourceList`. The
  children were full `SqlProfile`s with their own `RuntimeMetrics` — so
  the per-child *metrics* the plan treated as an open question were free
  all along, and the new Avro record only had to carry a slim view.
- **"The digest renders nowhere" — it did.** The detail pane already
  showed `digest` (labeled `perf_schema`) and `normalizedDigest`. What was
  missing was a copy affordance, not the rendering. There was also more
  precedent than the plan knew: `SqlGraphService` already builds a Grafana
  `var-digest=` URL and a Rundeck job running exactly the
  `events_statements_summary_by_digest` query the plan proposed writing —
  against hardcoded hosts. That reframed the decision as "promote those to
  per-environment config" vs "self-contained copy", and the copy won.
- **"Bumps the normalizer version story" — it doesn't.** Normalizer
  `VERSION` is about *digest identity*; this change never touches
  `QueryNormalizer`, it only surfaces what the normalizer already computed.
  Adding a defaulted Avro field re-keys nothing.
  [normalization-versioning.md](normalization-versioning.md) is
  independent of this work and stays deferred.
- **Two things the plan didn't know.** `SqlProfile.digest` is *not* the
  MySQL digest — `complete()` overwrites it with the normalized digest, so
  every member of a group reports the same value there and only
  `RuntimeMetrics.digest` keeps the performance-schema one. And a merge
  group's members are not one-per-digest: in the live corpus a profile had
  22 members and a single distinct digest, which is why the UI counts
  distinct digests.

## What shipped

1. **Avro.** `MergedChild { digest, repeatedBlocks, executed, averageTime }`
   and `array<MergedChild> mergedChildren = []` on `SqlProfile`, defaulted
   so the schema stays compatible. Registered with the
   `SpecificRecordBaseSerde` mixin — without it Jackson walks into the
   generated `getSchema()` and truncates the response, the same bug the
   `RepeatedBlock` mixin fixed.
2. **Merge-time capture.** The normalized display text and its repeats are
   now located *before* the merge check rather than after, so a child has
   its own counts to contribute; offsets still line up because the whole
   group normalizes to the same text. The child's MySQL digest is read off
   its `RuntimeMetrics`.
3. **Parquet.** The per-row write is gone. It serialized a representative
   before any child had merged into it, and merged children returned before
   the write entirely — so groups could not have survived a reload however
   they were recorded. The builder tracks which profiles own a row
   (representatives and ungrouped queries, never children) and writes them
   once the load is complete. `ParquetSink.close()` no longer reads a
   footer-less file and prints it to stdout, and `readAll` names BigDecimal
   in `parquet.avro.serializable.classes` — without which the reader
   rejects the profile schema's `@java-class` stringables, which is why
   nothing had ever read the file back.
4. **UI.** A "merged N variants" expander in the workbench detail, N being
   distinct digests, with executions, execution-weighted average time, the
   collapsed-repeat counts, and a `differs` flag where they diverge from
   the representative. Copy buttons on the digest boxes and on every row.

## Known limitation (pre-existing, untouched)

Rows are processed on a work-stealing pool and `attemptMerge` checks
`containsKey` without holding a lock across the representative's eventual
`addToIndex`, so two rows sharing a normalized digest can both miss the
check and each become a representative. `mergeByDigest` still groups them
for display, but the persisted `mergedChildren` will be short. That race
predates this work; the merge tests drive the builder seam directly rather
than pretend it isn't there.

## Effort: delivered at roughly S–M — smaller than the original M once the
premises were corrected.
