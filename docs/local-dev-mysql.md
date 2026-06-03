# Local Development MySQL with Performance Schema Data

## Goal

Replace the requirement of connecting to a live MySQL instance by providing a self-contained Docker Compose setup that:

1. Runs a MySQL 8.4 container with a realistic test schema
2. Pre-populates it with data
3. Generates realistic query traffic so that `sys.x$statement_analysis` (the view the app reads) has meaningful rows
4. Wires into the existing docker-compose stack with minimal config changes

---

## How the App Reads Data

The app connects to two datasources:

| Key | Purpose | Current source |
|---|---|---|
| `datasources.metadata` | Performance schema + table metadata | Live MySQL pointed to by `DB_HOST` |
| `datasources.explorer` | App's own storage | `explorer-mysql` container |

The primary query is against `sys.x$statement_analysis`, which is a view on top of `performance_schema.events_statements_summary_by_digest`. That view aggregates all executed statements by their normalized digest — the rows only exist after real queries have run.

---

## Architecture

Add two services to `docker-compose.yml`:

```
dev-mysql          — MySQL 8.4, test schema, performance_schema enabled
dev-workload       — lightweight container that runs query loops against dev-mysql
```

`sql-explorer` is then pointed at `dev-mysql` via a new Docker Compose profile (`dev-data`), avoiding any change to the default `DB_HOST` path used for real environments.

```
                        ┌─────────────────┐
                        │   dev-workload  │  (continuous query loops)
                        └────────┬────────┘
                                 │
  ┌──────────────┐   metadata    ▼         ┌──────────────┐
  │ sql-explorer │ ──────────► dev-mysql   │ explorer-    │
  │              │ ◄──────────             │ mysql        │
  └──────────────┘  explorer   (schema +   └──────────────┘
                               perf_schema)
```

---

## MySQL Configuration

MySQL 8.4 ships with `performance_schema=ON` and `statements_digest` consumer enabled by default, so no special `my.cnf` is required for basic operation. However, two settings improve the quality of data for local dev:

```ini
[mysqld]
# Increase digest history so slow/infrequent queries survive between restarts
performance_schema_events_statements_history_long_size = 10000

# Make sure full statement text is captured (default is 1024 bytes, truncates long queries)
performance_schema_max_digest_length = 4096
performance_schema_max_sql_text_length = 4096
```

Mount this as `dev-mysql/my.cnf` → `/etc/mysql/conf.d/perf-schema.cnf` inside the container.

After startup, the init SQL should also explicitly enable the long-history consumer at runtime (it's sometimes off by default):

```sql
UPDATE performance_schema.setup_consumers
SET ENABLED = 'YES'
WHERE NAME IN (
    'events_statements_history_long',
    'statements_digest'
);

UPDATE performance_schema.setup_instruments
SET ENABLED = 'YES', TIMED = 'YES'
WHERE NAME LIKE 'statement/%';
```

---

## Test Schema Design

Use a small e-commerce schema — enough tables and foreign keys to exercise join analysis, full-table-scan detection, and the table relationship graph.

```
dev_schema
├── categories        (id, name, parent_id)         -- self-referential FK
├── products          (id, name, price, category_id) -- FK → categories
├── users             (id, email, username, created_at)
├── orders            (id, user_id, status, created_at) -- FK → users
├── order_items       (id, order_id, product_id, quantity, price) -- FK → orders, products
├── posts             (id, user_id, title, body, created_at) -- FK → users
├── comments          (id, post_id, user_id, body, created_at) -- FK → posts, users
├── tags              (id, name)
└── post_tags         (post_id, tag_id)             -- junction, FK → posts, tags
```

Key design decisions:
- `categories.parent_id` is a nullable self-FK — exercises how the app handles recursive relationships
- `order_items` has two FKs on the same table — exercises multi-FK join analysis
- Some columns intentionally lack indexes (e.g. `orders.status`, `users.username`) to produce full-scan queries that appear in the FULL SCAN report
- Insert ~500 rows per major table so query plans are non-trivial

---

## Init Scripts

Mount a directory of numbered SQL scripts to `/docker-entrypoint-initdb.d/`. MySQL executes them alphabetically on first startup.

```
dev-mysql/
  init/
    01-schema.sql         -- CREATE DATABASE, CREATE TABLE statements
    02-data.sql           -- INSERT data (using procedures to generate volume)
    03-perf-schema.sql    -- Enable consumers, set instruments
    04-initial-traffic.sql -- Run a burst of queries to seed sys.x$statement_analysis
```

### `04-initial-traffic.sql` approach

A stored procedure loops through query patterns to generate the initial burst. This means data is available immediately when the container starts, before `dev-workload` joins.

```sql
DELIMITER $$
CREATE PROCEDURE generate_traffic(IN iterations INT)
BEGIN
  DECLARE i INT DEFAULT 0;
  WHILE i < iterations DO
    -- simple pk lookup (high exec count, fast)
    SELECT * FROM dev_schema.users WHERE id = (i MOD 500) + 1;

    -- join query (tests join analysis)
    SELECT o.id, u.email, o.status
    FROM dev_schema.orders o
    JOIN dev_schema.users u ON o.user_id = u.id
    WHERE o.status = 'pending'
    LIMIT 20;

    -- full table scan (no index on username — appears in FULL SCAN report)
    SELECT * FROM dev_schema.users WHERE username LIKE 'user%';

    -- aggregate (tests criteria column detection)
    SELECT COUNT(*) FROM dev_schema.order_items WHERE order_id = (i MOD 200) + 1;

    -- multi-table join (tests complex join analysis)
    SELECT oi.quantity, p.name, p.price
    FROM dev_schema.order_items oi
    JOIN dev_schema.products p ON oi.product_id = p.id
    JOIN dev_schema.orders o ON oi.order_id = o.id
    WHERE o.user_id = (i MOD 100) + 1;

    SET i = i + 1;
  END WHILE;
END$$
DELIMITER ;

CALL generate_traffic(200);
```

---

## Continuous Workload Generator (`dev-workload`)

The `dev-workload` container ensures `sys.x$statement_analysis` stays populated after restarts and accumulates exec counts over time (exec count is a key metric in the app's "Pain" and "Chatty" reports).

**Implementation**: a minimal Docker image (e.g. `mysql:8.4` reusing the client, or `alpine` + `mysql-client`) running a shell loop:

```dockerfile
# dev-workload/Dockerfile
FROM mysql:8.4
COPY workload.sh /workload.sh
RUN chmod +x /workload.sh
ENTRYPOINT ["/workload.sh"]
```

```bash
#!/bin/bash
# workload.sh — wait for dev-mysql then loop queries indefinitely
until mysql -h dev-mysql -u root -ppassword dev_schema -e "SELECT 1" >/dev/null 2>&1; do
  echo "Waiting for dev-mysql..."; sleep 3
done

echo "dev-mysql ready, starting workload"
while true; do
  # Randomize the user ID to vary query parameters
  USER_ID=$(( RANDOM % 500 + 1 ))
  ORDER_ID=$(( RANDOM % 200 + 1 ))

  mysql -h dev-mysql -u root -ppassword dev_schema -e \
    "SELECT * FROM users WHERE id = ${USER_ID};"
  mysql -h dev-mysql -u root -ppassword dev_schema -e \
    "SELECT o.id, u.email FROM orders o JOIN users u ON o.user_id = u.id WHERE o.id = ${ORDER_ID};"
  mysql -h dev-mysql -u root -ppassword dev_schema -e \
    "SELECT * FROM users WHERE username LIKE 'user_${USER_ID}%';"

  sleep 10
done
```

---

## Docker Compose Changes

Add both services under a `dev-data` profile so they don't start by default (they're opt-in for local dev only):

```yaml
  dev-mysql:
    image: mysql:8.4.4
    container_name: dev-mysql
    profiles: [ "dev-data" ]
    ports:
      - "3938:3306"
    environment:
      MYSQL_ROOT_PASSWORD: password
      MYSQL_DATABASE: dev_schema
    volumes:
      - ./dev-mysql/my.cnf:/etc/mysql/conf.d/perf-schema.cnf:ro
      - ./dev-mysql/init:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-ppassword"]
      interval: 10s
      timeout: 5s
      retries: 10

  dev-workload:
    build: ./dev-workload
    container_name: dev-workload
    profiles: [ "dev-data" ]
    depends_on:
      dev-mysql:
        condition: service_healthy
```

---

## App Configuration

Add a new application profile config that points `metadata` at `dev-mysql`. Create `src/main/docker-local/config/application-devdata.yml`:

```yaml
datasources:
  metadata:
    jdbcUrl: "jdbc:mysql://dev-mysql:3306"

x-statement-analysis:
  hosts:
    dev-mysql:
      jdbcUrl: ${datasources.metadata.jdbcUrl}
      username: root
      password: password
```

Update `src/main/docker-local/local.env` to activate this profile when running with `dev-data`:

```env
# Change this line when using the dev-data Docker Compose profile:
# ENVIRONMENT_PROFILE=localdev,devdata,override
ENVIRONMENT_PROFILE=localdev,override
```

The `sql-explorer` service in `docker-compose.yml` also needs `dev-mysql` as a conditional dependency:

```yaml
  sql-explorer:
    # existing config...
    depends_on:
      - explorer-mysql
      # Add when using dev-data profile:
      # - dev-mysql
```

Because Docker Compose doesn't support conditional `depends_on` based on profiles cleanly, the simplest approach is to add `dev-mysql` to `sql-explorer`'s `depends_on` list unconditionally — services not in the active profile are simply ignored by Compose.

---

## Files to Create

```
dev-mysql/
  my.cnf
  init/
    01-schema.sql
    02-data.sql
    03-perf-schema.sql
    04-initial-traffic.sql

dev-workload/
  Dockerfile
  workload.sh

src/main/docker-local/config/
  application-devdata.yml        (new profile config)
```

Modify:
- `docker-compose.yml` — add `dev-mysql` and `dev-workload` services
- `src/main/docker-local/local.env` — comment showing how to activate the profile

---

## Startup Sequence

```bash
# First time only (builds workload image, initializes schema + data)
docker compose --profile dev-data up -d dev-mysql
# Wait ~30s for init scripts to complete, then:
docker compose --profile dev-data up -d

# Start sql-explorer pointing at dev-mysql
# (set ENVIRONMENT_PROFILE=localdev,devdata,override in local.env first)
docker compose up -d sql-explorer explorer-ui
```

---

## Verification

After startup, confirm data is flowing:

```bash
# Should return rows with query digests and exec counts
docker exec dev-mysql mysql -uroot -ppassword \
  -e "SELECT digest_text, exec_count, avg_latency FROM sys.x\$statement_analysis LIMIT 10;"

# In the app: navigate to Tables → dev_schema
# The "Pain" column should show non-zero values
# Reports → Full Scan should list the username LIKE query
# Reports → Chatty should list the pk-lookup query (highest exec_count)
```

---

## Open Questions

- **Schema complexity**: The e-commerce schema covers the main analysis features. If specific MySQL patterns need testing (e.g. subqueries, CTEs, stored procedure calls), add targeted queries to `workload.sh`.
- **Data volume**: 500 rows/table is enough for non-trivial query plans. If index statistics matter (e.g. testing cardinality analysis), bump to 5000+ and run `ANALYZE TABLE` after inserts.
- **Password management**: The dev-mysql password (`password`) is hardcoded in `workload.sh` and `application-devdata.yml`. This is intentional for local dev convenience; it should never be used for anything accessible outside localhost.
