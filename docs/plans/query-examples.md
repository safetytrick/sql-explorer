# Capture example queries with their complete parameters

> **Status: design, 2026-08-28.** Bead `sqlx-hlu`. Nothing implemented. One
> question gates the first line of code — see [Open questions](#open-questions).

Analyzed profiles carry normalized SQL with the literals stripped. That is
what makes merging work and it is why nothing in the corpus can be run. An
*example* is the missing half: one real statement, parameters intact, tied to
the normalized group it belongs to.

`sqlx-cgh` (live EXPLAIN) needs this. A digest gives you `WHERE id = ?`,
which no server will plan.

## The design decision this turns on

**The endpoint must not accept a digest. It derives one.**

The obvious shape is `POST /analyzed-sql/{digest}/examples` with the
statement in the body. It is wrong: nothing checks that the submitted text
actually normalizes to the digest in the path. A caller can attach any
statement to any group — not maliciously, just by pasting into the wrong tab —
and the result is a stored example that lies about what it is an example
*of*. Every consumer downstream, `sqlx-cgh` included, would trust it.

The server already owns the function that answers this properly:

```java
// analysis/parser/NormalizedQueryDigest
public DigestedQuery digestedQuery(String sql, String sessionSchema) throws JSQLParserException
public String        normalizedDigest(String sql, String sessionSchema) throws JSQLParserException
```

So the request carries the **statement and its session schema**, and the
digest is computed, not asserted. The association becomes a derived fact.

### `sessionSchema` is the input that can still lie

Deriving the digest closes the hole the digest opened and moves it one field
along. `NormalizedQueryDigest.scopedDigest` keys by session schema — that is
deliberate, and recent (`7bdf0bc`, `bde1d69`): the same statement text in two
schemas is two groups, not one.

The failure modes are not symmetric, which is the point:

- A **wrong digest** is detectably wrong. Re-derive and compare.
- A **wrong session schema** yields a perfectly valid digest *for the wrong
  scope*. The example attaches cleanly to a different statement's group and
  nothing anywhere can tell.

And it fails quietly by default: `schemaScope` folds `null`, blank and the
`dual` placeholder to `""`, so an omitted schema does not error — it keys as
schema-less, which is itself a real scope. Omission is indistinguishable from
"this ran with no default database".

**Proposed: `sessionSchema` is required, and absent means absent.** Make the
caller say `""` (or an explicit "no schema" flag) rather than letting a
missing field mean it. The cost is one required field; the alternative is a
class of mis-attached examples that no later validation can find.

## What gets stored

Follow `commentary/Note` — JPA entity, `@ManyToOne User author`, `@CreatedDate
Instant createdOn`, repository interface, resource class. Table via a new
changeset in `src/main/resources/db/migration/db.changelog-master.xml`, the
single master changelog every other table lives in.

| Column | Type | Notes |
|---|---|---|
| `id` | `binary(16)` or `char(36)` | UUIDv7 — see below |
| `query_text` | `text` | the statement, parameters intact. The payload. |
| `normalized_digest` | see below | **derived**, never accepted. Indexed. |
| `session_schema` | `varchar(255)` | the scope the digest was derived under |
| `description` | `varchar(1024)` null | optional note on where it came from |
| `environment_name` | `varchar(255)` | matches `source_ref`, not a numeric id |
| `author_id` | `int` FK `users(id)` | `on update cascade on delete restrict` |
| `created_on` | `timestamp` | `not null default current_timestamp` |

**`environment_name`, not an environment id.** Environments are config-keyed
by name (`environments.<name>` in `application.yml`, selected per-request by
the `X-Sql-Explorer-Environment` header). There is no numeric id to reference.
`source_ref.environment_name varchar(255)` is the precedent.

**`author_id` is nominal until auth exists.** `auth/TODO.md` — everything is
attributed to the seeded admin (id 1). Same default here; the column is right
even while the value is uninteresting.

### Which digest, and how wide

There are **two** digests in this codebase and they must not be blurred.
`SqlProfileExtension:164` is explicit: `digest` is *MySQL's own*, from
Performance Schema. `normalizedDigest` is this application's
`scopedDigest` — SHA-256 over the normalized form, scoped by session schema.

**This column holds `normalizedDigest`.** The MySQL digest is not stable
across the normalization this feature exists to see through.

Width depends on a decision that is not this bead's to make.
`docs/plans/normalization-versioning.md` proposes storing digests as
`v<N>:<sha256>` — self-describing, and 64 characters plus a prefix.

- If that lands first: `varchar(72)`, and store the prefixed form.
- If it does not: `char(64)`, bare hex, as the bead specifies.

Choosing `char(64)` and later adopting the prefix is a migration to redo.
`varchar(72)` today costs nothing and accommodates both. **Recommend
`varchar(72)`**, storing bare hex until the versioning scheme exists.

### Why examples survive a normalizer change, and tags do not

This is the first thing in the Explorer database to durably key on a digest.
Tag paths embed one (`/query/{digest}`, `commentary/PathService`) but that is
a string, not a column.

`normalization-versioning.md` spells out the hazard: every improvement to
`QueryNormalizer` silently re-keys part of the corpus, and digest-keyed tag
paths orphan on a version bump. Its proposed repair for tags is involved —
find the profile whose *query text* re-normalizes to the new digest, then
rewrite the path.

**Examples need none of that, because the statement is the stored payload.**
A version bump re-derives every example's digest by replaying the current
normalizer over `query_text`. It is an `UPDATE` over one table, no search, no
guesswork, no orphans. The plan's hard case is hard because a tag path retains
only the digest; an example retains the thing the digest came from.

Worth stating plainly so nobody "optimizes" it later by storing the digest and
dropping the text.

## UUIDv7

**Settled (Michael, 2026-08-28): add the dependency** rather than hand-rolling
a generator.

```
implementation 'com.fasterxml.uuid:java-uuid-generator:5.2.0'
```

Verified against Maven Central, which is the only repository this build
declares (`build.gradle:27` — `mavenCentral()`; the Artifactory proxy in this
environment is npm-side and does not affect Gradle). `5.2.0` resolves; its
only runtime dependency is `slf4j-api`, already on the classpath via Spring
Boot. v7 arrives as `Generators.timeBasedEpochGenerator()`.

This is the **first non-`IDENTITY` primary key in the schema** — every
existing entity (`Tag`, `Note`, `User`, `SqlSourceEntity`) is
`@GeneratedValue(strategy = IDENTITY)`. Two consequences:

- The value is generated application-side. MySQL's `UUID()` is v1, and
  `java.util.UUID` has no v7 factory — which is what the dependency is for.
- **Column type still needs choosing.** `binary(16)` is compact and indexes
  tightly; `char(36)` is legible when someone hand-queries the explorer
  database, which on a tool like this happens. Time-ordering — the reason for
  v7 — holds either way, since v7 is big-endian-timestamp-first and sorts
  correctly as bytes *and* as text.

Ordering is the point: the retrieval API returns examples **DESC by id**,
which is most-recent-first precisely because the key is time-based. That is
the whole reason not to use a random UUID here.

## Retrieval

`GET` examples for a digest, **DESC by `id`**, paginated with a limit. Every
read is "the examples for this digest", which is what the index on
`normalized_digest` is for.

**An example whose digest matches no current profile is still stored and
still returned.** The corpus is a live window — the `regression` environment
reads `sys.x$statement_analysis`, and a statement drops out of it the moment
it stops being among the top consumers. Rejecting an example because the
corpus does not currently contain its group would make write availability a
function of import state, and would discard exactly the examples worth
keeping: the rare expensive statement that is not in this window.

## Open questions

**1. Scope — the one that gates implementation.** Performance Schema yields
normalized SQL only, so examples cannot be harvested from the existing import.
They arrive by hand or from a source carrying full statements. Three shapes,
and this was asked but not yet answered:

  - **Write endpoint only.** Migration, entity, repository, `POST` + `GET`,
    tests. Smallest thing that makes the data real and unblocks `sqlx-cgh`.
  - **Endpoint + a UI affordance** — a paste-an-example control on the SQL
    view. More work; the thing that actually gets examples entered, since
    nobody populates a table with `curl` twice.
  - **Endpoint + an importer** for a source with complete statements (slow
    query log, CSV). Largest; worth it only if such a source is in hand.

**2. Unparseable statements.** `digestedQuery` throws `JSQLParserException`,
and `/util/normalize` currently swallows parse failures into a
`"/* parse error */"` prefix rather than failing. An example that will not
parse has **no derivable digest**, so under this design it cannot be attached
to anything. Reject it with the parse error, or store it unattached for a
human to look at? Rejecting is simpler and consistent with "the association is
derived, not asserted"; storing unattached invents a second state that
every reader then has to handle. **Recommend rejecting**, with the parser's
message returned.

**3. `binary(16)` vs `char(36)`** for the id column — see above. Legibility
against 20 bytes a row, on a table nobody expects to be large.

## Effort

S–M for the endpoint-only shape, once question 1 is answered. M–L with a UI
affordance. The migration, entity and repository are all pattern-matching
against `Note`; the only genuinely new thing in the endpoint-only shape is the
UUIDv7 key.
