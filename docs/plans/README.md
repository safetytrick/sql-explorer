# Implementation Plans

One plan per open beads item (`bd ready`), grounded in code inspection. Effort:
S = hours, M = 1–3 days, L = a week+.

A plan lives here only while its work is unfinished. Completed plans are
deleted rather than tombstoned — the implementation is in the code and the
reasoning is in the commit that closed it.

| Plan                                     | Item                                                                                                    | Effort            |
|------------------------------------------|---------------------------------------------------------------------------------------------------------|-------------------|
| [legacy-ui.md](legacy-ui.md)             | Lens migration — one deletion decision left (`TableViewComponent`, `TableGraph*`)                       | S remaining       |
| [history-rewrite.md](history-rewrite.md) | Scrub older git history (working-tree scrub already done)                                               | S + coordination  |
| [mcp-server.md](mcp-server.md)           | MCP server — shipped; **awaiting Michael's testing** before the item closes                             | —                 |
| [live-explain.md](live-explain.md)       | Live EXPLAIN against the environment's servers (`sqlx-cgh`) — decided, not started                      | M–L               |

Supporting plan (not tracked as a bead, unscheduled, stands on its own):
[normalization-versioning.md](normalization-versioning.md). It is less often
a prerequisite than it looks — normalizer `VERSION` governs digest identity,
so surfacing already-computed data behind a defaulted Avro field re-keys
nothing. The repeat-merge work shipped without touching it.

## Suggested order

**legacy-ui** is down to a single deletion pass — decide whether
`TableViewComponent` and `TableGraph*` go, then execute. → **history-rewrite**
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

**live-explain** is the one item here that can execute a statement against an
analyzed server — everything else in this app only reads schema metadata and
`performance_schema`. Its three gating requirements are requirements, not
suggestions, and the one that carries the weight is that the endpoint takes a
digest and never a statement.
