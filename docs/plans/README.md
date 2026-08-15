# Implementation Plans

One plan per open WORK_TODO item, grounded in code inspection. Order below
follows `docs/WORK_TODO.md` (2026-08-04 revision). Effort: S = hours,
M = 1–3 days, L = a week+.

| Plan                                     | Item                                                                                                    | Effort            |
|------------------------------------------|---------------------------------------------------------------------------------------------------------|-------------------|
| [legacy-ui.md](legacy-ui.md)             | Lens migration — **nearly done**; one deletion decision left (`TableViewComponent`, `TableGraph*`)      | S remaining       |
| [history-rewrite.md](history-rewrite.md) | Scrub older git history (working-tree scrub already done)                                               | S + coordination  |
| [mcp-server.md](mcp-server.md)           | MCP server — shipped; **awaiting Michael's testing** before the item closes                             | —                 |
| [explain-parser.md](explain-parser.md)   | Explain parser — paste formats shipped; **awaiting Michael's testing** (live EXPLAIN option documented) | —                 |

Supporting plan (not a WORK_TODO item, feeds the normalizer follow-ups):
[normalization-versioning.md](normalization-versioning.md).

Completed plans are removed from the tree (their full content lives in
git history): views-vs-tables, parser-test-framework, secret-scrub,
normalizer, graph-generalization, gradle-docker-cleanup,
tag-widget-cleanup, graph-url-state. Newly completed, still in the tree
with a status block until the next docs commit: repeat-merge-ui (merge
groups persisted to parquet + the workbench expander; its status block
also corrects three premises the plan had wrong, which is why it is worth
reading before the next plan is written).

Note: repeat-merge-ui turned out **not** to require
[normalization-versioning.md](normalization-versioning.md) — normalizer
`VERSION` governs digest identity, and surfacing already-computed repeat
data behind a defaulted Avro field re-keys nothing. That plan stands on
its own and is unscheduled.

## Suggested order

**legacy-ui** is down to a single deletion pass — decide whether
`TableViewComponent` and `TableGraph*` go, then execute. → **history-rewrite**
(one coordinated cutover, any time).

Since the 2026-08-04 revision the UI work has largely landed: the SQL view
restyle, the removal of the reports and tag-index pages, Bootstrap 4 dropped,
`/schemas` and `/tables` rebuilt on the Ledger, `TagField` replacing
`app-tag-view`, the header tag filter strip, and `/tags` as master/detail.
Two small items came out of it and are logged in `WORK_TODO.md` rather than
given plans: the missing `**` catch-all route, and the `/tables` type filter
not being URL-backed.

**mcp-server** and **explain-parser** sit at the bottom deliberately:
both are shipped and awaiting Michael's hands-on testing before the items
are closed (or follow-ups get scheduled).
