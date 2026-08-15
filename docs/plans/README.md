# Implementation Plans

One plan per open WORK_TODO item, grounded in code inspection. Effort:
S = hours, M = 1–3 days, L = a week+.

A plan lives here only while its work is unfinished. Completed plans are
deleted rather than tombstoned — the implementation is in the code and the
reasoning is in the commit that closed it.

| Plan                                     | Item                                                                                                    | Effort            |
|------------------------------------------|---------------------------------------------------------------------------------------------------------|-------------------|
| [legacy-ui.md](legacy-ui.md)             | Lens migration — one deletion decision left (`TableViewComponent`, `TableGraph*`)                       | S remaining       |
| [history-rewrite.md](history-rewrite.md) | Scrub older git history (working-tree scrub already done)                                               | S + coordination  |
| [mcp-server.md](mcp-server.md)           | MCP server — shipped; **awaiting Michael's testing** before the item closes                             | —                 |
| [explain-parser.md](explain-parser.md)   | Explain parser — paste formats shipped; **awaiting Michael's testing** (live EXPLAIN option documented) | —                 |

Supporting plan (not a WORK_TODO item, unscheduled, stands on its own):
[normalization-versioning.md](normalization-versioning.md). It is less often
a prerequisite than it looks — normalizer `VERSION` governs digest identity,
so surfacing already-computed data behind a defaulted Avro field re-keys
nothing. The repeat-merge work shipped without touching it.

## Suggested order

**legacy-ui** is down to a single deletion pass — decide whether
`TableViewComponent` and `TableGraph*` go, then execute. → **history-rewrite**
(one coordinated cutover, any time).

**mcp-server** and **explain-parser** sit at the bottom deliberately: both are
shipped and awaiting Michael's hands-on testing before the items are closed
(or follow-ups get scheduled).
