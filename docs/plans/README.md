# Implementation Plans

One plan per open beads item (`bd ready`), grounded in code inspection. Effort:
S = hours, M = 1–3 days, L = a week+.

A plan lives here only while its work is unfinished. Completed plans are
deleted rather than tombstoned — the implementation is in the code and the
reasoning is in the commit that closed it.

| Plan                                     | Item                                                                                                    | Effort            |
|------------------------------------------|---------------------------------------------------------------------------------------------------------|-------------------|
| [legacy-ui.md](legacy-ui.md)             | Lens migration — `TableViewComponent` retired; the graph half is `sqlx-m0x`, **deferred**               | parity work       |
| [history-rewrite.md](history-rewrite.md) | Scrub older git history (working-tree scrub already done)                                               | S + coordination  |
| [mcp-server.md](mcp-server.md)           | MCP server — shipped; **awaiting Michael's testing** before the item closes                             | —                 |
| [query-examples.md](query-examples.md)   | Capture example queries with their parameters (`sqlx-hlu`) — design done, scope question open           | S–M / M–L         |

Supporting plan (not tracked as a bead, unscheduled, stands on its own):
[normalization-versioning.md](normalization-versioning.md). It is less often
a prerequisite than it looks — normalizer `VERSION` governs digest identity,
so surfacing already-computed data behind a defaulted Avro field re-keys
nothing. The repeat-merge work shipped without touching it.

## Suggested order

**legacy-ui** is half done. `TableViewComponent` is retired (`sqlx-b50`,
2026-08-28) — its two routes redirect to the lens rather than 404, because
deleting them outright would have let `table/:schema/:name/:column` match
`/table/x/y/queries` and render a confident wrong page. What is left is not a
deletion: the legacy graph does things the lens graph cannot, so `TableGraph*`
is parity work, tracked as `sqlx-m0x` and deferred. → **history-rewrite**
(one coordinated cutover, any time).

**mcp-server** sits at the bottom deliberately: it is shipped and awaiting
Michael's hands-on testing before the item is closed (or follow-ups get
scheduled).

The explain-parser plan is gone — `sqlx-mr0` was validated on 2026-08-27 and
closed, so the plan was deleted per the rule above. Its one unfinished piece,
running `EXPLAIN` against live servers, was `sqlx-cgh`. That was a decision
rather than a task and carried no plan doc; Michael decided on 2026-08-28 to
build it, so it is a task now and [live-explain.md](live-explain.md) is its
plan.

**query-examples** is the prerequisite nobody scheduled: a normalized digest
gives you `WHERE id = ?`, so live EXPLAIN has nothing runnable until examples
exist. Its design turns on one rule — the write endpoint *derives* the digest
from the submitted statement rather than accepting one, because nothing
otherwise checks that the text matches the digest it claims.

**live-explain is shipped** (`sqlx-cgh`, 2026-08-28) and its plan is deleted
per the rule above. It remains the one thing in this application that runs a
statement of our choosing against an analyzed server, so what guards it is
documented in the README rather than only in the code: the environment opts in
(`explainEnabled`, false by default), every endpoint takes an identifier and
declares no request body, `EXPLAIN ANALYZE` is refused because it executes
rather than plans, and each call is time-boxed. What gets planned is a stored
query example — the corpus holds `?` placeholders, which `EXPLAIN` rejects
outright.
