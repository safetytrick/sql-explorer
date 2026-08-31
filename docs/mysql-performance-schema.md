# Configuring MySQL so sql-explorer has something to read

Everything sql-explorer knows comes from one view: `sys.x$statement_analysis`,
which aggregates `performance_schema.events_statements_summary_by_digest`. If
Performance Schema is not recording statements, sql-explorer has nothing to
analyze — and, more dangerously, it will show you a small corpus rather than an
error.

This page covers only the settings that are specific to using MySQL *as a
source for sql-explorer*. For everything else — what Performance Schema is, how
to size it, what each table holds — read [MySQL's own
documentation](https://dev.mysql.com/doc/refman/8.4/en/performance-schema.html);
it is better than anything restated here would be.

**Everything below was measured against a stock `mysql:8.4.4` container**, the
version this project targets. Defaults have moved between MySQL releases, so
check your own server rather than trusting the numbers here for a different
version — `/coverage` in the running app reports what your capture actually
saw.

---

## The short version

| Setting | Stock 8.4.4 | Action |
|---|---|---|
| `performance_schema` | `ON` | verify |
| `statements_digest` consumer | `YES` | verify |
| `statement/%` instruments | 199 of 214 enabled | verify |
| `sys` schema and `sys.x$statement_analysis` | present | verify |
| `max_digest_length` | `1024` | **change to 4096** |
| `performance_schema_max_digest_length` | `1024` | **change to 4096** |
| `performance_schema_digests_size` | `10000` | raise on a busy server |

Only the bottom three need touching on a stock 8.4.4. The rest are already
right, and are listed so you can rule them out rather than because they need
work.

```ini
[mysqld]
max_digest_length                    = 4096
performance_schema_max_digest_length = 4096
```

All three of these are **read-only variables**: they cannot be set with `SET
GLOBAL` and require a server restart.

---

## Digest length: both variables, or neither

This is the setting that matters most, and it is the one most commonly given as
half the advice.

Statements longer than the digest limit are **truncated before their digest is
computed**. That is the wrong failure to have, because the queries that exceed a
kilobyte are exactly the large, generated, many-column statements someone opens
this tool to find. Worse, two different statements truncated at the same point
produce the *same* digest, so they are merged into a single profile and their
metrics are added together as though they were one query.

The advice usually given is to raise `performance_schema_max_digest_length`.
**On its own, that does nothing.** There are two variables:

- **`max_digest_length`** governs *computing* the digest.
- **`performance_schema_max_digest_length`** governs *storing* it.

Performance Schema cannot store more of a digest than the server computed, so
**the effective limit is the lower of the two**. Raising only one leaves the
other binding.

### The measurement

A 3,606-byte statement (`SELECT 1 AS column_name_number_0001, …` with 120
aliases) run against four stock `mysql:8.4.4` containers, reading
`LENGTH(DIGEST_TEXT)` back out of
`performance_schema.events_statements_summary_by_digest`:

| `max_digest_length` | `performance_schema_max_digest_length` | stored digest | |
|---|---|---|---|
| 1024 | 1024 | 1027 | cut |
| 1024 | 4096 | 1027 | cut — raising one changed nothing |
| 4096 | 1024 | 1027 | cut |
| 4096 | 4096 | 3964 | kept |

`DIGEST_TEXT` comes back at the limit plus three characters, which is the `...`
appended to a truncated digest.

To reproduce, with a query file longer than 1024 bytes at `q.sql`:

```bash
docker run -d --name dtest -e MYSQL_ROOT_PASSWORD=password mysql:8.4.4 \
  --max-digest-length=4096 --performance-schema-max-digest-length=4096
```

```bash
docker cp q.sql dtest:/tmp/q.sql && docker exec dtest sh -c 'mysql -uroot -ppassword < /tmp/q.sql' && docker exec dtest mysql -uroot -ppassword -N -e "SELECT LENGTH(DIGEST_TEXT) FROM performance_schema.events_statements_summary_by_digest ORDER BY 1 DESC LIMIT 1;"
```

sql-explorer detects the symptom — it marks those profiles **truncated**, and
`/coverage` reports both the count and the effective limit, naming which of the
two variables is binding.

---

## Digest table capacity

`performance_schema_digests_size` (default `10000`) is how many distinct
statement digests the server will hold. Once it is full the server **stops
taking on new digests** — it does not evict old ones to make room. Everything
already in the table keeps counting accurately; what is lost is every query
shape that first ran after the table filled, which lands against a `NULL`
digest instead.

That distinction is worth holding onto, because it says which half of the data
to trust: the counts you can see are right, and it is the **absences** that are
unreliable. "No query touches this table" is exactly the claim a full digest
table can falsify.

The server counts what it discarded:

```sql
SHOW GLOBAL STATUS LIKE 'Performance_schema_digest_lost';
```

Anything above zero means statements were dropped. sql-explorer records this
value with every capture and reports it on `/coverage`, so you do not have to
watch it yourself — but if it is climbing, raise `performance_schema_digests_size`
and restart.

Whether 10000 is enough depends entirely on how many *distinct* query shapes
your application has, not on how much traffic it serves. An application with 200
query patterns will never come close; one with generated SQL can exhaust it in
hours.

---

## Performance Schema is cleared on restart

This surprises people, and it shapes what the data can be used for: **the digest
table is emptied when the server restarts.** The corpus only ever covers the
period since the last restart, whatever the application's actual age.

That is why sql-explorer records an *observed window* with every capture and
states it on `/coverage` and on `/unqueried`. "No query touches this table" is
a claim about the window, not about the table — across 118 days it means
something, across two days it means almost nothing.

Corroborate it with uptime when a window looks wrong:

```sql
SHOW GLOBAL STATUS LIKE 'Uptime';
```

sql-explorer captures this too, and flags a window that is longer than the
server has been running, because then one of the two is wrong.

---

## Verifying instrumentation

Stock 8.4.4 already has these right, so this is a check rather than a step. If
a corpus comes back empty, this is the first thing to rule out:

```sql
SELECT NAME, ENABLED FROM performance_schema.setup_consumers WHERE NAME = 'statements_digest';
SELECT COUNT(*) FROM performance_schema.setup_instruments WHERE NAME LIKE 'statement/%' AND ENABLED = 'YES';
SELECT COUNT(*) FROM information_schema.VIEWS WHERE TABLE_SCHEMA = 'sys' AND TABLE_NAME = 'x$statement_analysis';
```

Expect `YES`, a number near 199, and `1`. A hardened or heavily tuned server may
have disabled statement instrumentation, in which case the digest table stays
empty however long the server runs.

---

## Grants for the reading account

sql-explorer reads; it never writes to the server it analyzes. The account it
connects with needs three things, and the third is the one that is easy to
miss.

```sql
CREATE USER 'sql_explorer'@'%' IDENTIFIED BY '…';
GRANT SELECT ON sys.* TO 'sql_explorer'@'%';
GRANT SELECT ON performance_schema.* TO 'sql_explorer'@'%';
-- one line per schema you want analyzed
GRANT SELECT ON your_app_schema.* TO 'sql_explorer'@'%';
```

- **`sys.*`** — for `sys.x$statement_analysis` itself.
- **`performance_schema.*`** — the view reads through to it.
- **`SELECT` on each analyzed schema** — for the *metadata*, not the data.
  `information_schema` only shows an account the objects it holds some privilege
  on, so without this the tables, columns and foreign keys come back **empty
  rather than failing**. A schema that appears to have no foreign keys is
  usually a missing grant rather than a missing constraint.

`SHOW VARIABLES` and `SHOW GLOBAL STATUS`, which sql-explorer reads alongside
the rows to record what the server was configured like at capture time, need no
additional privilege. **`PROCESS` is not required** — the digest table is
aggregate, not per-session.

Verified by creating exactly this account on a stock 8.4.4 and running
sql-explorer's own queries as it.

---

## Reading the result

`/coverage` in the running app reports what a capture actually saw, judged
against the settings above: whether the digest table was at capacity, how many
statements were truncated and what the effective limit was, the observed window,
how many hops from `sys.x$statement_analysis` the data is, and whether the
digest table was reset between two captures.

One thing to know about it: **coverage reads these settings from the capture,
never from a live connection.** Change a setting and restart, and the change
appears only in captures taken afterwards. That is deliberate — attaching
today's values to data read months ago, possibly from a different server, would
be a confident wrong answer — not a stale cache.

See [running sql-explorer](running-sql-explorer.md) for how to point the tool at
a server once it is configured.
