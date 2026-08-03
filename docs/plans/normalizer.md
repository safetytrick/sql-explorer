# Normalize repeated SQL blocks before normalized hashing

> **Status: implemented 2026-08-02.** `QueryNormalizer` now collapses, as an
> AST pass before the digest: duplicate predicates in flattened AND/OR chains
> (recursing through grouped conditions, subselects, join ON conditions,
> UPDATE/DELETE wheres), duplicate CASE WHEN arms, duplicate IN-list
> elements, and duplicate batched-INSERT VALUES rows. Dedup is by exact
> serialized form of the recursively-normalized sibling, so distinct
> literals never merge, and function argument lists are never touched
> (arity is semantic). `NormalizedQueryDigest` unchanged — it already hashes
> `normalize()` output. The `// todo` at `SqlEvidenceInputService` is
> resolved.
>
> **Follow-up (same day): repeat metadata + RunFinder removal.**
> `QueryNormalizer.normalizeWithRepeats` reports each collapsed block's kept
> snippet + occurrence count in serialization order; `RepeatLocator`
> (token-based, whitespace/case-insensitive matching) resolves them to
> `[start, end)` offsets in the formatted display text; they persist as
> `SqlProfile.repeatedBlocks` (new Avro record) and render as "snippet ×N"
> chips under the SQL in the queries workbench detail. **RunFinder is
> removed**: the stored display text is now the formatted *normalized* SQL
> when parsing succeeds; unparseable SQL keeps the formatted raw text set at
> the top of `processRow` (that was RunFinder's only remaining value —
> decision: raw passthrough, no token-level condensing). A snippet the
> locator can't find is dropped rather than misattributed. Scheme evolution:
> see [normalization-versioning.md](normalization-versioning.md).
> Tests: new `NormalizedQueryDigestTest` (repeat-variant pairs hash equal;
> distinct literals and different patterns don't) and `QueryNormalizerTest`
> extensions (per-construct collapse equivalences + non-collapse guards).
> **Digest migration decision:** no re-keying pass. Digests for
> repeat-variant queries change on next import, which re-shuffles merge
> groups (that's the point) and orphans any tags keyed on those digest paths
> — accepted, since data is re-imported per load and digest-keyed tags
> already orphan whenever a query pattern naturally changes. If orphaning
> proves painful, a follow-up could match old→new digests by re-normalizing
> tagged queries at import time.

## Current state

The pipeline: `SqlCleaner` (string pre-pass,
`analysis/parser/SqlCleaner.java:8-40`) → `SqlParser` → `QueryNormalizer`
(strips select-item aliases only — `SelectItemNormalizer` at
`QueryNormalizer.java:55` is the *only* normalization) → SHA-256 in
`NormalizedQueryDigest.java:22-32` → merge-by-digest in
`SqlEvidenceInputService.processRow` (`:254-261`; a digest hit skips analysis
entirely via `attemptMerge`, `:391-416`).

**A repeated-block collapser already exists but runs after hashing, for display
only**: `RunFinder` (`analysis/parser/RunFinder.java`) finds maximal tandem
token repeats and replaces them with `~|unit|~[count]` markers. It's invoked at
`SqlEvidenceInputService.java:287-297` only when `nestingLevel > 5`, guarded by
the comment `// todo, refactor and solve this in the normalizer` — that TODO is
this WORK_TODO item. Consequence of the current ordering: two queries differing
only in repeat count (e.g. 10 vs 12 `AND col LIKE ?` arms) hash differently
(`:255` hashes the uncondensed text) but can *render* identically after
condensing.

Per-category handling today: `?`-runs and `...` collapse in `SqlCleaner` (`:11`,
`:28`); nested `IF()` chains collapse (`collapseIfStatements`, `:44-51`);
repeated OR/AND and CASE arms — display-only via `RunFinder`; batched
`INSERT ... VALUES (...),(...)` — nothing anywhere. Tests:
`QueryNormalizerTest` has one test; `NormalizedQueryDigest` has none;
`RunFinderTest` has two monster-query tests asserting marker counts.

## Proposed approach

Move repeat-collapsing to before the digest, as an AST pass in
`QueryNormalizer` rather than the token-level `RunFinder`:

1. Extend `NormalizerVisitor` to walk WHERE/HAVING expressions and collapse
   runs of structurally-identical siblings in `AND`/`OR` chains and `CASE`
   arms (compare serialized subtree with placeholders); collapse
   multi-row `ExpressionList` in `Insert` VALUES to one row; collapse
   `IN`-list elements. Keep `RunFinder` as display fallback for unparseable SQL.
2. Compute the digest from the collapsed AST (`NormalizedQueryDigest` needs no
   change — it already reparses and hashes `normalize()` output).
3. Tests first: characterize current digests for repeat-variant pairs
   (10-arm vs 12-arm `LIKE` chains, 1-row vs 3-row inserts) so the change in
   merge behavior is explicit. Add the missing `NormalizedQueryDigestTest`.
4. Note the blast radius: changed digests re-shuffle merge groups
   (`mergeByDigest`, `AnalyzedSqlRepository.java:67-91`,
   `CompositeSqlRepository.java:103-110`) and any tags keyed on digest paths
   (`commentary/PathService.java`). Data is re-imported per load, so no stored
   migration — but saved tag paths that embed digests will orphan.

## Open questions

- Is orphaning digest-keyed tags acceptable, or do we need a tag re-keying
  pass on next import?
- Should collapsed counts be preserved (e.g. store `repeatCount`) for Pain
  weighting, or is pure dedup fine?

## Effort: M. The AST pass is fiddly; tests are most of the work.
