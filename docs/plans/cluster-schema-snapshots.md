# Clusters and schema snapshots

Decided with Michael on 2026-10-19 in an interview. It reorganizes how an
environment of RDS servers is configured, and how it gets its schema.

## Why

Today a production environment of RDS servers is a flat list of 19 peers:
every primary, replica and ETL instance is listed as its own server. Each one is
a capture source, which is right. Each one is also a schema source, which isn't:

- **Wasted work.** Startup reads 19 schemas at about 3s each, though shards 1–4
  and their replicas carry the same application schema. Since `sqlx-3js`, only
  the first server's copy of a table is ever used.
- **No grouping.** Nothing in the config says which instances are replicas of
  which primary, so there's nothing to fall back to and nothing to compare
  against.
- **Nothing persists.** A restart re-reads every schema before any analysis can
  run, and an unreachable server means no schema at all.

## Decisions

| Question | Decision |
|---|---|
| Layout | One environment, with servers nested under **clusters** (one per primary: shard1, shard2, …) |
| Where schema comes from | Each cluster's schema servers, named explicitly with **`schemaFrom`** |
| Schema server unreachable | **Fall back** to the next server in `schemaFrom`, and say on `/coverage` which one answered |
| When schema loads | From a **persisted snapshot**, checked against a cheap live **fingerprint** |
| Stale snapshot | Old results are served at once. **New analysis waits** until the snapshot is current |
| Digests analysed before | **Always reused**, with no parsing, even across schema changes — per **analyser version** |
| No fresh schema possible | Never-seen digests **wait, counted as pending** on `/coverage`. Nothing is analysed against a stale snapshot |
| Shards defining a table differently | First wins for lookups, and the **drift is reported** |
| UI | **Config only for now.** No cluster scope in URLs; `/coverage` groups sources by cluster |
| Server names | **Unchanged** (`shard1-replica-a`), so existing captures stay attached |

### The layout

```yaml
environments:
  production:
    clusters:
      shard1:
        schemaFrom: [shard1, shard1-replica-a, shard1-replica-b]
        servers:
          shard1:
            jdbcUrl: "jdbc:aws-wrapper:mysql://…"
            username: "reader"
            sources:
              - type: x-statement-analysis
          shard1-replica-a: …
          shard1-etl: …        # captured from, never a schema source
      shard2: …
```

- **`schemaFrom` order:** the primary first, then the replicas. ETL instances
  are left out.
- **Generating it.** A real fleet's file is best generated from the RDS API by
  a site-specific script, which belongs in the git-ignored `scripts/`; a
  generated file should be regenerated rather than hand-edited.
- **Server names stay unique across the whole environment.** Captures are keyed
  by server name.
- **Environments without clusters keep working.** A flat `servers:` block
  (`regression`, `local`) means one cluster per server, with
  `schemaFrom: [itself]`.

## How it behaves

### Startup

1. **Serve what was captured.** Every environment is rebuilt from its latest
   captures under the schema snapshot those captures were analysed with, so
   pages work at once.
   - This reverses a deliberate choice in `sqlx-4ww` ("startup stays live").
     That choice assumed a live read was cheap and always at least as complete.
     It's neither of those once a production environment takes minutes to
     reach. The live pull still happens, just after the first page can be
     served.
2. **Check each cluster's snapshot.** One fingerprint query goes to the first
   reachable `schemaFrom` server. If it matches, the snapshot is current. If
   not, that server's schema is read in full, and the result is persisted as
   the new snapshot.
3. **Pull live captures.** For each captured row:
   - **Digest analysed before:** reuse that analysis. No parsing, and no wait
     for the schema.
   - **Never seen:** analyse it once its cluster's snapshot is current. Until
     then, count it as **pending**.

### Fingerprint

A single query per cluster, over the `information_schema` tables the loader
already reads:
- `COLUMNS`
- `STATISTICS`
- `KEY_COLUMN_USAGE`
- `TABLES` (comments)

It hashes per table, e.g. `BIT_XOR(CRC32(CONCAT_WS(…)))` grouped by schema and
table, so the same result also says *which* tables changed, which drift
reporting needs. Under the metadata-only grant profile (`REFERENCES, SHOW VIEW`)
every input is readable. `VIEW_DEFINITION` isn't (see
`mysql-performance-schema.md`), so leave it out of the hash.

### Reusing a digest's analysis

The source is the existing captures. They store complete analysed `SqlProfile`s
(tables, columns, failure flags), so the reuse index is "latest analysed profile
per key" across the archive, and needs no new store.

The key is **(MySQL digest, default schema, analyser version)**, not the
digest alone. MySQL's
digest is computed from the normalised text only, so the same unqualified
statement under two default schemas has one digest but resolves to different
tables.

### Snapshots

- **Per cluster**, stored with the captures' archive, and recording:
  - the fingerprint
  - which server answered
  - when it was read
- The snapshot's own `TableStructure`s replace a live `SchemaRepositoryFactory`
  load whenever the fingerprint matches.

### Pending and fallback on `/coverage`

Per cluster:
- which server supplied the schema, and whether that was a fallback
- the snapshot's age
- **how many digests are pending**, if the snapshot couldn't be refreshed

A cluster with pending digests is a corpus with an unanalysed part, and it has
to say so (the same principle as `sqlx-jdf`).

### Drift

Clusters that carry the same schema name (every shard carrying the same application schema) are
compared per table by fingerprint. A table whose definition differs between
clusters is reported, and its lookups still use the first cluster's definition.
A migration applied to some shards but not others then shows up, instead of
being masked by first-wins.

## Analyser version

"Reuse forever" holds for a given version of sql-explorer's own analysis. The
reuse key is **(MySQL digest, default schema, analyser version)**. The
analyser version is a constant, bumped whenever a change to parsing or the
analyzers would change what a profile says, in the same way
`QueryNormalizer.VERSION` already versions digests.

A schema change never invalidates a reused analysis. A new analyser version
does: a digest analysed by an older version counts as never seen, and waits
for a current snapshot like any new digest (decided 2026-10-19).

## Phases

Each phase is a bead under the epic. In dependency order:

1. **Config model:** clusters and `schemaFrom` in `EnvironmentDefinition` and
   `EnvironmentRegistry`; flat `servers:` still accepted.
2. **Schema per cluster:** the composite is built from clusters, not servers.
   Schema loads from `schemaFrom` with fallback, and `/coverage` names the
   server that answered.
3. **Fingerprint:** the query, the per-table hash, and a test against the grant
   profiles in `GrantProfilesProbeTest`.
4. **Snapshots:** persist per cluster; load from the snapshot when the
   fingerprint matches.
5. **Analysis gating and digest reuse:** the reuse index from captures; pending
   digests; analysing them when the snapshot becomes current.
6. **Startup from captures:** serve first, pull second (revisits `sqlx-4ww`).
7. **Drift:** a per-table fingerprint comparison across clusters sharing a
   schema, reported on `/coverage`.
