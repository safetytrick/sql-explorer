
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

Queries and Tables can be tagged within SQL Explorer. 

Table tags can be used to render customized graphs (from the Tags menu item). These show query relationships between 
tables in the same tag and tables outside of that tag.

Query tags allow you to organize and report on query patterns (to prioritize indexing changes or rewrites).

### Joins and Foreign Keys

SQL Explorer does not use Foreign keys to determine relationships between columns, instead it uses the actual joins 
within the analyzed SQL. SQL Explorer aims to surface actual relationships including tables that are queried together 
even if these tables are not joined directly to each other. This relationship is described as "queried with" in the 
table Graph UI.

## Reports

SQL Explorer contains a handful of built-in reports.

### Analysis Related Reports

Analysis Failures
: Queries that failed one or more analysis stage (typically due to unimplemented visitors)

Parse Failures
: Queries that could not be parsed for an unknown reason (potentially truncated but may be caused by ambiguous syntax)

Truncated
: Queries that failed parsing because the query was truncated


### Query Behavior Reports

All
: All queries, sorted entirely by dormancy and pain

Mismatched Joins
: Identifies queries across columns that use incompatible charsets

Slow
: Queries with a high average latency

Chatty
: Queries that are fast, but executed often

Full Scan
: Queries that have ever caused a Full Scan

Multi Schema
: Queries that join tables across separate schemas  

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


Database Configuration
----------------------

Environment configuration:

`application.yml`

```yaml
performance-schema-file-loader:
  enabled: true
  directory: /etc/sql-explorer/config/statement-analysis
  pattern: "(?<id>dc-mysql-\\d+)-(?<date>\\d{8})\\.tsv"
  date-format: "yyyyMMdd"

datasources:
  metadata:
    jdbcUrl: "jdbc:mysql://schema-mysql:3306"
    
  explorer:
    jdbcUrl: "jdbc:mysql://explorer-mysql:3306/explorer"

x-statement-analysis:
  enabled: true
  hosts:
    staging-db-master:
      jdbcUrl: ${datasources.metadata.jdbcUrl}
```

Secrets:

`application-secrets.yml`:
```yaml
datasources:
  metadata:
    username: root
    password: password

  explorer:
    username: root
    password: password

x-statement-analysis:
  enabled: true
  hosts:
    staging-db-master:
      jdbcUrl: ${datasources.metadata.jdbcUrl}
      username: ${datasources.metadata.username}
      password: ${datasources.metadata.password}
```
