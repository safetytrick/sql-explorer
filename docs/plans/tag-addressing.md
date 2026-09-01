# What a tag points at: re-evaluating the path

Michael asked two things on 2026-09-01: whether a 191-character unique-key
prefix on `tags.path` is sufficient, and whether the path concept is a "cute"
solution that should be reconsidered.

Short answers: **the prefix is provably sufficient**, and **yes, worth
reconsidering — but not because of length, and not for the reason that looked
strongest.**

## The length question is settled

MySQL caps identifiers at 64 characters. Verified against the running server:
`information_schema.SCHEMATA.SCHEMA_NAME` and `TABLES.TABLE_NAME` are both
`varchar(64)`. The application constructs exactly three path shapes
(`ui/src/app/tag-path.ts`):

| shape | maximum |
|---|---|
| `/schema/{schema}` | 8 + 64 = **72** |
| `/schema/{schema}/table/{table}` | 8 + 64 + 7 + 64 = **143** |
| `/query/v{n}:{sha256 hex}` | 7 + 4 + 64 = **75** |

143 is the ceiling, and the prefix is 191. That is 48 characters of headroom
against a hard limit, not against a sample — so the key is exactly as strict as
it reads, and no pair of distinct paths can collide in the indexed prefix.

The longest path in the corpus today is 52. But the bound is what matters, and
the bound holds.

**So if the only question were length, the answer would be "no change needed".**

## The defect that is actually there

MySQL identifiers may contain `/` when quoted. Confirmed by creating a table
literally named `a/b`, then tagging it through the live API:

```
POST /tags  {"name":"probe","path":"/schema/pathprobe/table/a/b"}
->  "type": "a"
    "pathElements": {"schema": "pathprobe", "table": "a"}
```

The tag was filed under a type called `a`, which does not exist, and its
`pathElements` report the table as `a` rather than `a/b`. A tag with
`type = 'a'` is invisible to `findAllByType("table")`, so no lens, no filter and
no bulk operation would ever find it — and a bogus type appears in the tag
manager's name list. The row is not recoverable by any normal path in the UI.

Two things to be precise about, because they decide how urgent this is:

- **The unique key is unaffected.** Two distinct paths cannot agree on their
  first 191 characters under a 143-character ceiling, so this is a *parsing*
  defect, not a *keying* defect. Nothing shipped in `sqlx-rad` is wrong.
- **The path is client-supplied.** `TagsResource.createTag` takes `path` from
  the request body and derives `type` from it by regex. Nothing validates that
  the path is one of the three known shapes, so the malformed row above is
  reachable by any client, not only by an oddly-named table.

## Why "just fix the parsing" is not quite enough

The parsing bug is fixable in one place: derive `type` by matching the *known*
prefixes rather than by the tail regex `([^/]+)/[^/]+$`. That is an afternoon's
work and closes the reproduced defect.

But a table named `a/b` still cannot be *addressed*. Fixing that properly means
encoding the segments, and encoding is what destroys the length guarantee above:
percent-encoding is 3 bytes per escaped character, so a 64-character identifier
can reach 192, and

```
/schema/{192}/table/{192}  =  8 + 192 + 7 + 192  =  399
```

At that point the 191-character prefix is no longer provably safe — it is merely
safe for the identifiers people actually use. **Under the path model, "fix the
bug properly" and "keep the bound" pull against each other.** That tension, not
the current length, is the real argument for changing the model.

## What typed columns would and would not buy

Replacing the string with the fields it encodes:

```
environment_name  varchar(255)
target_type       varchar(16)     -- SCHEMA | TABLE | QUERY
schema_name       varchar(64)
table_name        varchar(64)
query_digest      varchar(72)
unique key (environment_name, target_type, schema_name, table_name, query_digest)
```

**WOULD buy:**

- **No prefix and no bound to reason about.** 471 characters × 4 bytes = 1884,
  comfortably inside InnoDB's 3072. The question Michael asked stops existing.
- **No parsing at all.** `type` is stored, not derived, so the reproduced defect
  cannot occur.
- **No encoding.** A table named `a/b` is just a value in a column.
- **Queries that mean what they say.** `findAllByTypeAndPathStartsWith` — a
  `LIKE 'prefix%'` used to load every tag under one schema — becomes
  `WHERE target_type = 'TABLE' AND schema_name = ?`.

**WOULD NOT buy, and this is where the attractive argument fails:**

- **No referential integrity.** The explorer database holds no row for a schema
  or a table — they are introspected from a *different* server. There is nothing
  for a foreign key to point at, under either model. A tag on a dropped or
  renamed table dangles the same way it does today.
- **No fix for digest orphaning.** This is the one worth stating loudly, because
  it looked like the strongest reason to redesign and it does not hold.
  `normalization-versioning.md` step 3 exists to re-key tag paths when
  `QueryNormalizer.VERSION` bumps, and it is tempting to say typed columns make
  it unnecessary. They do not. `SqlProfileExtension` assigns
  `id = digest` when no id is set, and `SqlProfileResource` resolves a query by
  digest — **the digest *is* a query's identity**. There is no version-stable
  handle to store instead. Moving the digest out of a string and into its own
  column relocates the problem without solving it; step 3 is still needed
  either way.

## Cost

Contained, and concentrated in the parsers rather than spread across the app.

**Four parsing mechanisms** — the whole of the risk:

| site | what it does |
|---|---|
| `Tag.PATH_ELEMENTS` regex → `getPathElements()` | consumed at 5 call sites (`SqlTagsService` ×3, `QuerySearchService` ×2) |
| `TagsResource.pathTypePattern` | derives `type` on write |
| `findAllByTypeAndPathStartsWith…` | prefix scan for the bulk loader |
| the migration's `substring_index` | historical, already applied |

**Nine construct sites** are noise: the UI's are already centralized in
`tag-path.ts` (three one-line functions) and the backend's is one method in
`SqlRepositoryService`. Keeping `path` on the API as a **derived, read-only**
value means none of them change and neither does the UI.

Also worth deleting on the way past: **`PathService` has zero references.**

**Effort: S–M.** One migration with a backfill parsed from existing paths (39
rows, all well-formed), the four parsers replaced, `path` retained as derived
output.

## Recommendation

1. **Fix the type derivation and validate the path on write** (S). Do this
   regardless of the decision below — it closes a reproduced, live defect and
   does not depend on the model. Filed as `sqlx-0lj`.
2. **Then move to typed columns** (S–M), with `path` kept as a derived value so
   the UI and every construct site stay as they are. Filed as `sqlx-11v`.

Do **not** reopen `sqlx-rad`. It is closed and correct: the environment scoping
is right under either model, and the prefix it introduced is provably safe for
every path the application can build today.

And do not justify step 2 with the digest-versioning story. The honest case for
it is narrower and sufficient: no prefix to reason about, no parsing to get
wrong, and identifiers that MySQL permits but the path scheme cannot represent.
