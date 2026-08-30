# Grafana integration: what it actually is

A spike (`sqlx-cec`). Plan, not build.

The bead framed this as "Grafana **+ Prometheus**", and named one question as
the one to settle before anything else:

> Do the metrics being linked to exist? … either an external exporter supplies
> them and this integration just links to it, or producing those series is the
> real first task. Settle this before anything else — it decides whether the
> item is a linking feature or an exporter.

Settled, against the live production Grafana. The answer is **neither**, and it
changes the design rather than choosing between the two options offered.

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

## The reframing: this is not an exporter

The bead's two options were "link to an exporter someone else runs" or "build
the exporter". Both assume the metrics must become Prometheus series. They must
not.

**Per-digest series would be a cardinality disaster.** The regression corpus
alone holds 4,243 digests. Multiply by instances, and by the several
`performance_schema` digests a normalized profile covers, and this is tens of
thousands of series whose labels are opaque 64-character hashes. That is the
textbook Prometheus anti-pattern, and no retention policy makes it pleasant.

**And it is unnecessary, because Grafana can already read the source
directly.** `sys.x$statement_analysis` and
`performance_schema.events_statements_summary_by_digest` live on the very
servers Grafana already has 16 datasources for. A "SQL analysis" dashboard can
query them live, filtered by digest, from the same tables sql-explorer reads —
no exporter, no new series, no cardinality problem, and no second copy of the
truth to drift.

So the integration is a **linking feature after all**, but the thing it links
to is a dashboard over a MySQL datasource, not over Prometheus. That is the
single decision this spike exists to make, and everything below follows from it.

One consequence worth stating plainly: **`events_statements_summary_by_digest`
is cumulative-since-restart, not a time series.** A dashboard over it shows
totals and averages as they stand, and `$__timeFilter` has nothing to bind to
except `FIRST_SEEN` / `LAST_SEEN`. If genuine per-digest *trend* is wanted, that
needs periodic sampling into a table — which is exactly the shape `my2.status`
already is, and the natural place to add it. Trend is a second, larger piece of
work; do not let it block the linking feature, which is useful without it.

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

### The multi-digest case, corrected

The bead says the regex case "maps onto a Prometheus label matcher rather than a
value: `digest=~"a|b|c"`". With the Prometheus premise gone, so does that. It
maps onto a **SQL predicate**:

```sql
WHERE DIGEST IN ($digest)        -- Grafana expands a multi-value variable
```

This is the more important half of the feature, not a refinement. A sql-explorer
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
