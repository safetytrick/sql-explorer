# Running sql-explorer

How to stand the tool up and point it at a database. For the MySQL side — which
Performance Schema settings matter and what goes wrong at the defaults — see
[configuring MySQL](mysql-performance-schema.md). Read that one first if you
control the server being analyzed; the defaults are wrong for this tool in one
specific and silent way.

This page is for people *running* sql-explorer. If you are working on the code,
`CLAUDE.md` at the repository root is the orientation for that.

---

## What you need

- Docker and Docker Compose
- A MySQL server to analyze, with Performance Schema recording statements, and
  an account that can read it
- MySQL 8.4 for sql-explorer's own storage — the compose file brings this up
  for you

sql-explorer never writes to the server it analyzes. It keeps its own state
(tags, notes, uploaded sources) in a separate database.

---

## Starting it

Services must come up in order — the backend will not start against a MySQL
that is not yet accepting connections:

```bash
docker compose up -d explorer-mysql
```

```bash
docker compose up -d sql-explorer
```

```bash
docker compose up -d explorer-ui
```

The UI is then at `http://localhost:4200`, and the API at
`http://localhost:18180/api/sql-explorer` — the backend listens on 8080 inside
the container and the compose file publishes it on 18180. Note the context
path: every endpoint on this page is relative to `/api/sql-explorer`.

Optionally, to load shared table and query tags:

```bash
docker compose up explorer-shared-tags
```

The first import is CPU-heavy: every statement is parsed and analyzed. On a
corpus of a few thousand digests expect the backend to take twenty to thirty
seconds before it starts serving.

---

## Where configuration lives

Four files, in increasing order of specificity:

| File | Holds | Tracked in git |
|---|---|---|
| `src/main/resources/application.yml` | settings true for every deployment — port, context path, Liquibase | yes |
| `src/main/docker-local/config/application.yml` | environments, servers, sources, datasources — everything host-specific | yes |
| `src/main/docker-local/config/application-secrets.yml` | usernames, passwords, keys | yes, with local-only values |
| `src/main/docker-local/config/application-override.yml` | your own credentials, via `${DB_USERNAME}` / `${DB_PASSWORD}` from `.env` | **no** |

The `config/` directory is mounted into the container at
`/etc/sql-explorer/config`, so edits take effect on a restart of the backend —
no rebuild.

`ENVIRONMENT_PROFILE` in `src/main/docker-local/local.env` selects which
profiles are active; it defaults to `override`. `.env` is git-ignored and is
where real credentials belong.

---

## Environments and servers

An **environment** is a named thing you analyze — usually one deployment tier.
Each has one or more **servers**, and each server has one or more **sources**.

```yaml
environments:
  regression:
    default: true
    servers:
      primary:
        jdbcUrl: "jdbc:mysql://${DB_HOST}:3306"
        sources:
          - type: x-statement-analysis
```

Environments are independent: loading one does not touch another's data, and
each is analyzed and reported on separately. The UI's environment selector
picks between them; the API takes the same choice as an
`X-Sql-Explorer-Environment` header, defaulting to the environment marked
`default: true`.

One flag is worth knowing about:

```yaml
    explainEnabled: true
```

`EXPLAIN` is **off unless an environment opts in**, because planning a statement
runs it against a real server. Leave it off for anything you do not own.

### RDS servers with IAM authentication

An Amazon RDS server that authenticates an AWS IAM identity instead of a
password is configured like any other, with two differences: its `jdbcUrl`
goes through the [AWS JDBC wrapper](https://github.com/aws/aws-advanced-jdbc-wrapper)'s
`iam` plugin, and the backend runs with the `docker-compose.rds.yml` overlay so
the container can use your AWS session.

```yaml
environments:
  production:
    servers:
      primary:
        jdbcUrl: "jdbc:aws-wrapper:mysql://<instance-endpoint>:3306/?wrapperPlugins=iam&iamRegion=<region>&awsProfile=<profile>&sslMode=REQUIRED"
        username: "<iam-database-user>"
        sources:
          - type: x-statement-analysis
```

```bash
aws sso login --profile <profile>
docker compose -f docker-compose.yml -f docker-compose.rds.yml up -d sql-explorer
```

Put it in `src/main/docker-local/config/application-rds.yml`, which is
git-ignored and loaded as the `rds` profile that the overlay turns on. A real
fleet is easier to generate than to write by hand, from
`aws rds describe-db-instances`. Such a script is specific to one site's hosts
and naming, so keep it in `scripts/`, which is git-ignored for that reason.

Four things matter, whoever writes the file:

- **No token is written anywhere.** An IAM token is valid for 15 minutes and
  sql-explorer opens connections for hours, so a token pasted in as a password
  would break every connection after the first quarter hour. The wrapper signs
  a fresh token for every new connection instead. When your SSO session lapses,
  connections start failing; `aws sso login` on the host fixes it with no
  restart, because the overlay mounts `~/.aws` (read-only) into the container.
- **Use the instance endpoint, not a friendly CNAME.** IAM tokens are signed
  for the hostname they are presented to.
- **List every replica as its own server.** Each keeps its own digest table. A
  weighted DNS name for "the replicas" reaches one of them at a time.
- **Name servers by role, not by instance identifier.** Identifiers often carry
  a rebuild date, and captures are keyed by server name, so a dated name would
  split one server's history in two at the next rebuild.

The database user must exist on every instance, set up for IAM
(`AWSAuthenticationPlugin`), with the read grants in
[configuring MySQL](mysql-performance-schema.md#grants-for-the-reading-account).

Every environment is read at startup, so with this overlay each backend start
pulls from every server it lists.

#### Clusters

A primary and its replicas carry one schema, so reading it from each of them
is wasted work. Group them into a **cluster**: every server is still captured
from, but schema is read only from the cluster's `schemaFrom` servers, first
reachable wins. List the primary first, then replicas, and leave out anything
that may lag or differ, such as an ETL replica:

```yaml
environments:
  production:
    clusters:
      shard1:
        schemaFrom: [shard1, shard1-replica-a, shard1-replica-b]
        servers:
          shard1: { jdbcUrl: …, sources: [ { type: x-statement-analysis } ] }
          shard1-replica-a: …
          shard1-etl: …
```

Server names must be unique across the whole environment, not just within a
cluster, because captures are keyed by server name. A flat `servers:` block
still works: each server there is a cluster of one.

Each cluster's schema is **snapshotted** in `schema-snapshots/<environment>/`
next to the captures. A start or pull checks a fingerprint, one cheap query of
about 0.1s on a production primary plus the connection, and reads the full
schema only if it changed. If no `schemaFrom` server can be reached, the last
snapshot serves what was already analysed, marked stale on the Captured page.
Statements never analysed before then **wait**, counted as pending, rather than
being analysed against a schema that may have changed.

---

## Sources: the three shapes

A **source** is one body of statements. Every source ultimately originates in
`sys.x$statement_analysis`; what differs is how many hops away it is, and *when*
it was captured. sql-explorer records that with the data and reports it on
`/coverage`, because a copy of an export from a server nobody can query any more
supports weaker claims than a live read.

### 1. A direct read — `x-statement-analysis`

sql-explorer connects to the server and reads the view itself. This is the
strongest kind of source: the Performance Schema settings are read over the same
connection, in the same breath as the rows, so they genuinely describe the data.

```yaml
        sources:
          - type: x-statement-analysis
```

Credentials come from the server's `username` / `password`, kept in the secrets
or override profile.

### 2. Files on disk — `performance-schema-files`

A directory of TSV exports of the same view, discovered by pattern:

```yaml
        sources:
          - type: performance-schema-files
            directory: /var/sql-explorer/exports
            pattern: "statement-analysis-(\\d{8}).tsv"
            dateFormat: "yyyyMMdd"
```

All three of `directory`, `pattern` and `dateFormat` are required. Each file
found is persisted before being loaded, so it survives a restart.

Files discovered this way carry **no capture context** — nobody supplied one,
and sql-explorer will not invent it from the file's modification time. They load
fine; `/coverage` reports their digest settings as unknown rather than guessing.

### 3. An upload

`POST /sources` (multipart) takes a TSV export directly. `capturedAt` is
**required** and rejected if missing:

```bash
curl -F file=@statement-analysis.tsv -F sourceRef=prod-2026-08 \
     -F capturedAt=2026-08-29T14:03:35Z \
     -F serverVersion=8.4.8 -F serverDigestsSize=10000 -F serverMaxDigestLength=4096 \
     http://localhost:18180/api/sql-explorer/sources
```

It is required because it cannot be recovered later — only whoever ran the
export knows it, and upload time is not a substitute. The two are routinely
months apart, and every coverage signal hangs off the capture time rather than
the upload. The three `server*` fields are optional but worth supplying: without
them nothing can say whether that export was truncated or evicting digests.

The upload lands in the environment selected on the request.

---

## Reloading

Sources are loaded at startup and can be reloaded without a restart:

```bash
curl -X POST http://localhost:18180/api/sql-explorer/sources/load
```

```bash
curl -X POST http://localhost:18180/api/sql-explorer/environments/regression/load
```

The first reloads the environment selected on the request; the second names one
explicitly. Both take an optional JSON body listing which source refs to load;
with no body, every source configured for or attributed to that environment is
used.

A reload replaces that environment's data wholesale. Other environments are
untouched.

---

## Written captures, and what accumulates

Each import also writes a Parquet copy of the analyzed profiles, stamped with
its capture instant:

```yaml
parquet-sink:
  filename: /parquet/sql-repository.parquet
```

A configured `sql-repository.parquet` yields siblings named
`sql-repository-20260830T191229Z.parquet`, one per import. The newest **sealed**
file is the corpus; the older ones are the archive, and they are what lets
`/coverage` notice that Performance Schema was reset between two captures and
report the window nothing observed.

Three things to know about the directory:

- **Nothing prunes it.** Files accumulate one per import, about 4.2 MB for a
  corpus of ~4,200 profiles. On a container that restarts often this grows
  steadily. Automatic deletion is deliberately not implemented — the older
  captures are evidence, and silently discarding them is not a decision to make
  on your behalf. Decide on a retention policy, or budget for keeping
  everything.
- **The newest file is zero bytes while the app runs.** Parquet writes its
  footer at close, and sql-explorer closes at shutdown. This is normal, not
  corruption; the reader skips back to the newest sealed capture.
- **A hard kill leaves a stub.** A container stopped without a clean shutdown
  leaves a 4-byte file that never got a footer. `/coverage` reports these as
  captures that did not finish; they cost that sample but not the analysis.

---

## Checking that it worked

Once loaded, `/coverage` reports what the capture actually saw, per source:

```bash
curl -s http://localhost:18180/api/sql-explorer/coverage
```

or the **Captured** page in the UI. Read it before concluding anything from an
absence — it is the difference between "nothing uses this table" and "nothing
recorded it". It names whether the digest table was at capacity, how many
statements were truncated and against what effective limit, how long a period
the rows cover, how far from `sys.x$statement_analysis` they are, and whether
anything happened while nothing was watching.

If the corpus looks small or empty, work through
[configuring MySQL](mysql-performance-schema.md) — the usual causes are a digest
length limit left at its default, a missing `SELECT` grant on the analyzed
schema (which makes foreign keys and columns come back empty rather than
failing), or a server that restarted recently and cleared its digest table.

---

## Local development against a throwaway MySQL

`docs/local-dev-mysql.md` is a design proposal for a self-contained dev stack
with generated query traffic, so you can work on sql-explorer without pointing
it at a real server. It is a plan rather than a description — the services it
describes are not in `docker-compose.yml` yet.
