# Grafana integration: what it actually is

A spike (`sqlx-cec`). Plan, not build.

The bead framed this as "Grafana **+ Prometheus**", and named one question as
the one to settle before anything else:

> Do the metrics being linked to exist? … either an external exporter supplies
> them and this integration just links to it, or producing those series is the
> real first task. Settle this before anything else — it decides whether the
> item is a linking feature or an exporter.

Settled, against the live production Grafana: **the metrics do not exist
today**, and MySQL observability here does not go through Prometheus at all.
They can be created — Percona's `mysqld_exporter` is the standard way — but
there is also a route that needs no exporter, and the two differ in what they
can support. So the question resolves into a choice this spike frames rather
than makes.

---

## What is actually there

Checked against the `grafana-prod` instance on 2026-08-30. (`grafana-dev` and
the staging instance would not connect, so everything below describes prod and
should be re-checked if the others differ.)

**There is one Prometheus datasource — VictoriaMetrics — and it holds no MySQL
server metrics at all.** No `mysql_global_status_*`, no `mysql_perf_schema_*`,
nothing matching `digest`. What is there under a `mysql` search is Debezium
connector metrics and Kafka consumer lag. There is no `mysqld_exporter` feeding
it.

**MySQL observability at this org does not go through Prometheus.** It goes
through Grafana's own MySQL datasources — there are **16 of them**, one per
environment and tier (`mysql-prod-a`, `mysql-alpha-a`,
`mysql-prod-b`, …). The `App MySQL` and `Other MySQL` dashboards select
between them with a template variable and run SQL:

```sql
SELECT $__timeGroup(timest, '10m') AS time, variable_name AS metric, avg(variable_value+0)
  FROM my2.status
 WHERE $__timeFilter(timest) AND variable_name IN ('THREADS_CONNECTED', 'THREADS_RUNNING')
```

`my2.status` is a home-grown `(timest, variable_name, variable_value)` table of
scraped server status — per instance, nothing per digest. RDS logs and metrics
come from CloudWatch on a third path.

**The dashboard the existing hardcoded link points at does not exist.**
`SqlGraphService:34` builds
`https://grafana.local/d/dvUfZxpZk/sql-analysis?…&var-digest=%s&…`, and there is
no dashboard with uid `dvUfZxpZk` in this Grafana — nor any host `grafana.local`.
The neighbouring `RUNDECK_QUERY` constant and `BASE_URL =
"http://localhost:4200/analyzed-sql/"` are the same class of thing. This is
prior art in the sense of an idea, not code to extend; it is a dead link with a
`// todo refactor into a grafana integration plugin` beside it.

---

## Two routes, and what separates them

The bead's options were "link to an exporter someone else runs" or "build the
exporter". Neither is quite it.

**The exporter exists and is standard — Michael, 2026-08-30.** Percona's
`mysqld_exporter` fork (the one PMM ships) has a `perf_schema`
events-statements collector that emits per-digest series labelled by schema and
digest, sourced from the same `events_statements_summary_by_digest` this tool
reads. So this is not bespoke work: it is deploying a standard component that
nobody here runs yet. Check the exact flag names and defaults against the
version actually deployed rather than trusting this document; the collector is
opt-in and carries a **top-N limit** (a few hundred by default) for the reason
set out next.

**Cardinality is still the thing that shapes the design.** The regression
corpus alone holds 4,243 digests. Multiply by instances, and by the several
`performance_schema` digests a normalized profile covers, and unbounded
per-digest series are tens of thousands of them, labelled by opaque
64-character hashes. That is why the collector caps to top-N rather than
exporting everything — and the cap has a direct consequence for this
integration: **a link to a digest outside the top-N lands on an empty panel**,
and an empty panel has to be legible as "not exported" rather than as "no
traffic". sql-explorer sees the whole digest table; the exporter, by design,
does not.

**There is also a route that needs no exporter at all, because Grafana can
already read the source directly.** `sys.x$statement_analysis` and
`performance_schema.events_statements_summary_by_digest` live on the very
servers Grafana already has 16 datasources for. A "SQL analysis" dashboard can
query them live, filtered by digest, from the same tables sql-explorer reads —
no exporter, no new series, no cardinality problem, and no second copy of the
truth to drift.

So there are **two viable targets**, and the choice between them is the one
decision this spike leaves open rather than settles:

- **A dashboard over the MySQL datasources that already exist.** No new
  infrastructure, complete digest coverage, live. But
  `events_statements_summary_by_digest` is cumulative-since-restart, so it
  gives totals rather than trend.
- **PMM / Percona's exporter into Prometheus.** Real time series and real
  trend, at the cost of deploying and running it, and bounded to top-N digests.

They are not exclusive — the first is a way to have the linking feature working
before any exporter exists, and the link template shape below is the same
either way. Note also that if PMM is deployed, its **Query Analytics (QAN)**
gives a per-digest history view of its own, so the more useful link may be into
QAN rather than into a dashboard this repo ships.

One consequence worth stating plainly on the direct-SQL route:
**`events_statements_summary_by_digest` is cumulative-since-restart, not a time
series.** A dashboard over it shows totals and averages as they stand, and
`$__timeFilter` has nothing to bind to except `FIRST_SEEN` / `LAST_SEEN`. Trend
is exactly what the exporter route buys, and it is why that route is worth the
deployment rather than merely an alternative.

---

## What a link template is here

A Grafana deep link with the variables left open, filled in from whatever the
user is looking at. Concretely, per environment:

```
{baseUrl}/d/{dashboardUid}/{slug}?var-datasource={datasourceUid}&{targetVars}&from={from}&to={to}
```

- **`baseUrl` and `datasourceUid` are per environment.** sql-explorer already
  has an environment concept with per-server configuration
  (`environments.{name}.servers.{name}`), and a Grafana datasource maps onto a
  server one-to-one. That is the natural home: a `grafana:` block beside
  `jdbcUrl`, not a global constant. The current constant only ever worked for
  one deployment precisely because it had nowhere else to live.
- **The target vars depend on what is being linked**, below.
- **`from`/`to`** default to the observed window sql-explorer already records
  per capture (`/coverage`), which is a better default than `now-30d`: linking
  to a window the corpus does not cover invites reading a blank panel as
  "no traffic".

### The three targets

| From | Path (already the tagging key) | Grafana variable |
|---|---|---|
| a query profile | `/query/{normalizedDigest}` | `var-digest`, **plural** — see below |
| a table | `/schema/{s}/table/{t}` | `var-schema`, `var-table` |
| a schema | `/schema/{s}` | `var-schema` |

Using `PathService`'s existing path shapes as the key is what lets a template be
attached to *a kind of thing* rather than to a page, and it is the same
vocabulary tags already speak.

### The multi-digest case

The bead says the regex case "maps onto a Prometheus label matcher rather than a
value: `digest=~"a|b|c"`", and on the exporter route that is exactly right. On
the direct-SQL route it maps onto a **SQL predicate** instead:

```sql
WHERE DIGEST IN ($digest)        -- Grafana expands a multi-value variable
```

Either way this is the more important half of the feature, not a refinement. A sql-explorer
profile is a *normalized* pattern that covers several MySQL digests —
`SqlGraphService:139` uses `perfDigests.getFirst()` and silently discards the
rest, so the existing link shows one arbitrary member of the set. The useful
panel shows all of them together, and a multi-value Grafana variable does that
natively.

**Which digest matters**, restated because it is easy to get backwards:
`normalizedDigest` is sql-explorer's own sha256 identity and means nothing to
MySQL. The values that go in the link are the **`perfDigests`** — MySQL's own —
and a profile carries several.

---

## The open questions, and what is left of them

**Where does dashboard JSON live?** Nothing in the repo ships Grafana assets.
Proposal: `grafana/dashboards/*.json` at the repo root, with the compose stack
mounting it into a provisioning directory for local use and the docs describing
import for real deployments. Bundling it is what makes this ship something
usable rather than only plumbing — and the dashboard is where the SQL above
lives, so without it the templates point at nothing. Note the precedent this
sets: a dashboard in this repo has to keep working against whatever
`performance_schema` layout the target server has.

**Stored or generated?** Generated from configuration, not persisted as data.
The template is a property of an environment's server — the same place its
`jdbcUrl` lives — so it belongs in `application.yml` beside it and needs no
table, no CRUD and no migration. Persisting it would make it a thing to manage;
configuring it makes it a thing to set once.

**Still genuinely open, and the first thing to check before building:** can the
account Grafana's MySQL datasources connect with actually read
`performance_schema` on those servers? Everything above assumes it can, and
[docs/mysql-performance-schema.md](../mysql-performance-schema.md) sets out the
grants — `SELECT` on `sys.*` and `performance_schema.*` — which the Grafana
account may well not have, since today's dashboards only need `my2.status`. If
it cannot, the choice is a grant or a sampling table, and that is a
five-minute question to answer against one datasource.

**Not in scope, but it will come up:** per-digest trend over time, per the
cumulative-counter note above.

---

## What this replaces

`SqlGraphService`'s `GRAFANA_URL`, `RUNDECK_QUERY` and `BASE_URL` constants —
all three hardcoded, all three deployment-specific. Note they sit on the **v1**
graph service, which `docs/TECH_DEBT.md` lists as candidate dead API surface. So
this work should inherit the idea and let the deletion of v1 take the code with
it, rather than refactoring constants that may be about to disappear.
