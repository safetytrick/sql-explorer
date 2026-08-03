# Versioning and evolution of the normalization scheme

## Why

The normalized digest is the identity that merge groups, the
`normalizedDigest` Avro field, and digest-keyed tag paths all hang off
(`analysis/parser/NormalizedQueryDigest.java`, `SqlRepositoryBuilder
.attemptMerge`, `commentary/PathService`). The scheme just changed once
(alias stripping → alias stripping + repeat collapsing) and it will change
again — every improvement to `QueryNormalizer` silently re-keys part of the
corpus. Today nothing records *which* scheme produced a digest, so mixed
data is indistinguishable from matching data.

## Current state

- `QueryNormalizer` (`analysis/parser/QueryNormalizer.java`) is unversioned;
  its output feeds SHA-256 in `NormalizedQueryDigest`.
- Digests are persisted in `SqlProfile.normalizedDigest` (Avro → Parquet
  sink) and recomputed on every source load — in-memory repositories rebuild
  from scratch, so the *live* corpus is always single-version.
- Mixed versions can still meet: (a) Parquet files written by an older build
  and re-read later, (b) tags whose paths embed digests
  (`/analyzed-sql/<digest>/...`) created under an older scheme, (c) two
  environments loaded by different app builds but compared by a human.

## Proposed approach

1. **Version constant + tagged digests (S).** Add
   `QueryNormalizer.VERSION` (int, bump on any serialization-affecting
   change) and store digests as `v<N>:<sha256>` — self-describing, cheap to
   parse, and impossible to accidentally compare across schemes (the string
   differs). Add `SqlProfile.normalizerVersion` (int) alongside, so the raw
   hash stays available for tooling. Keep a table in the normalizer's
   javadoc: version → what changed (v1 alias stripping; v2 repeat
   collapsing + repeat metadata).
2. **Re-key on load, not in place (S).** Keep today's model: a source load
   recomputes everything with the current version, so the live corpus is
   uniform. Never mix versions inside one environment's repository; if a
   future Parquet-warm-start path lands, it must compare the file's
   `normalizerVersion` to `QueryNormalizer.VERSION` and re-normalize stale
   profiles instead of trusting stored digests.
3. **Tag migration (M, when needed).** Digest-keyed tag paths orphan on a
   version bump. Add an import-time re-key pass: for each tag path embedding
   a digest with an old version prefix, find the profile whose *query text*
   re-normalizes (current scheme) from the stored sample, and rewrite the
   path. Feasible because tags are few and the mapping old→new is computable
   from stored query text. Until implemented, bumps orphan digest tags —
   the accepted default recorded in
   [normalizer.md](normalizer.md).
4. **Utility surface (S).** `/util/normalize` should report the version it
   used, so saved comparisons are self-describing.

## Coexistence rules

- One environment = one version (enforced by recompute-on-load).
- Cross-environment comparisons (or saved digests in notes/tickets) rely on
  the `v<N>:` prefix to fail loudly rather than mismatch silently.
- The un-normalized `digest` (MySQL's own statement digest) is
  version-independent and remains the stable fallback key
  (`getDigestOrId` already prefers normalizedDigest → digest → id).

## Open questions

- Prefix format: `v2:<hash>` inside the existing field vs. a separate
  `normalizerVersion` int only? (Proposal: both — prefix for safety,
  field for queries.)
- Should the version bump automatically fail CI if `QueryNormalizerTest`'s
  canonical serializations change without a `VERSION` bump (golden-file
  test)?
- Is tag re-keying (step 3) worth building before the next scheme change,
  or acceptable to defer until digest-keyed tags are actually in wide use?
- Does the Parquet sink need the version in its file metadata (footer
  key/value) in addition to per-record fields?

## Effort: S for steps 1–2 + 4; M for tag migration.
