
sql-explorer
============

SQL Explorer is an application for analyzing and reasoning about the running SQL in your system. SQL Explorer is 
built on top of MySQL's [Performance Schema](https://dev.mysql.com/doc/refman/8.0/en/performance-schema.html). 
SQL Explorer attempts to parse and organize the SQL statements (including truncated statements) within the 
performance schema. Queries are then sorted and presented to the user based on the "Pain" or impact they have 
on MySQL (calculated from the total query execution time).  

SQL Explorer can analyze sql from multiple MySQL nodes in a clustered environment. SQL Explorer only reads metadata 
from one source. SQL Explorer is generally forgiving about mismatches between the metadata it knows about and the 
structure of queries it is asked to analyze.  

SQL Explorer attempts to provide insights into a SQL schema based on the queries that are run against the schema.
Queries are parsed and the columns and tables that they reference are used to organize and provide navigation throughout
the schema. Declared foreign keys and undeclared foreign keys are discovered through a combination of schema reflection
and parsing of queries. Transitive queried-with relationships between tables are also discovered (two tables may not be
directly joined but are used together in one query).

### SQL Pattern Merging

SQL Explorer attempts to merge metrics from query patterns that it considers to be similar. For instance these three 
queries are considered identical within SQL Explorer:

```sql
SELECT * FROM some_table;

SELECT name, value FROM some_table;

SELECT name _f0, value _f1 FROM some_table;
```

This may not be desired for cases where a covering index changes query performance characteristics, this is not a 
configurable behavior.  

### Tagging

Schemas, tables and queries can be tagged. Tags are ad-hoc labels — a way to mark a slice of the schema ("owned by
this service", "touched by the migration") and then filter, report and graph on it.

Tags are edited in place wherever the thing itself appears: the list pages carry an inline tag control on every row,
and the same control sits on the table and query views. The **Tags** page is the other direction — pick a tag and see
everything carrying it, across schemas, tables and queries, with membership editable from there.

The list pages also filter by tag, from a strip in the page header. Selections travel in the URL
(`?tags=a,b&tagMode=ALL|ANY`), so a filtered view is a link you can send.

### Joins and Foreign Keys

SQL Explorer does not use Foreign keys to determine relationships between columns, instead it uses the actual joins 
within the analyzed SQL. SQL Explorer aims to surface actual relationships including tables that are queried together 
even if these tables are not joined directly to each other. This relationship is described as "queried with" in the 
table Graph UI.

## The UI

- **Schemas** / **Tables** — dense ranked lists of what the analyzed queries touch, ordered by impact.
- **Queries** — the workbench: full-text, tag, activity, timing and behaviour filters (full scan, temp-to-disk,
  truncated, multi-schema, analysis failures …) over every analyzed query. The whole search lives in the URL.
- **Graph** — table relationships, from real joins and queried-with co-occurrence, scoped to a schema, a search or a tag.
- **Tags** — the tag manager and what carries each tag.
- **Explain** — paste an EXPLAIN plan and have it parsed.
- **Query Utilities** — normalize / clean a statement by hand.

Drilling into a table gives its columns, indexes, DDL, foreign keys and the queries that touch it — with per-column
and per-join query lists. Drilling into a query gives its cost, the indexes relevant to what it constrains, and the
patterns merged into it.

Ten canned reports (`slow`, `chatty`, `full-scan`, `truncated`, `multi-schema`, the analysis-failure sets, …) remain
available over the API at `/api/sql-explorer/analyzed-sql/reports/{name}` and through the MCP server. They have no
page of their own: the Queries workbench covers the same ground interactively, and composably.

Local Setup
-----------

Build the application with:

```bash
docker compose build
```

The backend image is a complete two-stage build (gradle runs inside the
image with a BuildKit cache mount for dependencies), so no JDK or gradle is
needed on the host. `./gradlew assemble` still works for running outside
Docker.

Startup the application with: `docker compose up -d sql-explorer`

sql-explorer parses and loads all statement-analysis sql on startup, this may take several minutes 
(monitor with `docker compose logs -f sql-explorer`)

Then startup the ui with: 

`docker compose up -d explorer-ui`

The UI will be accessible at [http://localhost:4200](http://localhost:4200)

Load table and sql tags (optional):

`docker compose up explorer-shared-tags`

Working on the code
-------------------

Backend (Java 21, Gradle) and frontend (Angular 21, in `ui/`) build independently:

```bash
./gradlew build
```

```bash
cd ui && npm install && npm start
```

`npm start` serves the UI on port 4200 against a backend on 8080. Production output goes to
`ui/build/sql-explorer-ui`.

Before pushing, run what CI runs (`.github/workflows/ci.yml`) — there is no pre-push hook:

```bash
./gradlew build
```

```bash
cd ui && npm run lint && npx tsc --noEmit -p src/tsconfig.app.json && npm run test:ci && npm run build
```

Configuration
-------------

Config lives in `src/main/docker-local/config/` for the local Docker stack. An environment is a named set of
servers, each with the sources to pull statement analysis from:

`application.yml`

```yaml
environments:
  regression:
    default: true
    servers:
      primary:
        jdbcUrl: "jdbc:mysql://${DB_HOST}:3306"
        sources:
          - type: x-statement-analysis

datasources:
  explorer:
    jdbcUrl: "jdbc:mysql://explorer-mysql:3306/explorer"

parquet-sink:
  filename: /parquet/sql-repository.parquet
```

Define more than one environment to switch between them in the UI; the frontend selects one with the
`X-Sql-Explorer-Environment` header.

Credentials go in a separate file, mirroring the same structure:

`application-secrets.yml`

```yaml
datasources:
  explorer:
    username: root
    password: password

environments:
  local:
    servers:
      explorer:
        username: root
        password: password
```

Further reading
---------------

- [ARCHITECTURE.md](ARCHITECTURE.md) — the domain concepts: relevancy sorting, hierarchical URLs, and what each
  module is responsible for.
- [CLAUDE.md](CLAUDE.md) — working conventions: repo layout, the frontend styling and URL-state rules, the gate,
  and the repo's sharper edges.
- Open work lives in **beads** (`.beads/`, the `bd` CLI) — `bd ready` for unblocked work. `docs/WORK_TODO.md` is
  now just a signpost to it.
- [docs/](docs/) — `plans/` for the longer design write-ups, `TECH_DEBT.md` for the standing inventory,
  `local-dev-mysql.md` for running MySQL locally.
