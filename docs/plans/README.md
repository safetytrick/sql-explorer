# Implementation Plans

One plan per open WORK_TODO item, grounded in code inspection. Order below
follows `docs/WORK_TODO.md` (2026-08-04 revision). Effort: S = hours,
M = 1–3 days, L = a week+.

| Plan | Item | Effort |
|---|---|---|
| [graph-url-state.md](graph-url-state.md) | Graph schema/table selection in URL params (+ `var-` prefix convention) | S–M |
| [repeat-merge-ui.md](repeat-merge-ui.md) | Visualize normalization merges: perf-schema digest link, per-child repeat differences | M |
| [legacy-ui.md](legacy-ui.md) | Update legacy UI pages to new formatting / finish the lens migration | M–L |
| [history-rewrite.md](history-rewrite.md) | Scrub older git history (working-tree scrub already done) | S + coordination |
| [mcp-server.md](mcp-server.md) | MCP server — shipped; **awaiting Michael's testing** before the item closes | — |
| [explain-parser.md](explain-parser.md) | Explain parser — paste formats shipped; **awaiting Michael's testing** (live EXPLAIN option documented) | — |

Supporting plan (not a WORK_TODO item, feeds the normalizer follow-ups):
[normalization-versioning.md](normalization-versioning.md).

Completed plans are removed from the tree (their full content lives in
git history): views-vs-tables, parser-test-framework, secret-scrub,
normalizer, graph-generalization, gradle-docker-cleanup,
tag-widget-cleanup.

## Suggested order

**graph-url-state** (small, pattern exists) →
**repeat-merge-ui** (the substantial new feature; coordinate with
normalization-versioning) → **legacy-ui** (rolling background work) →
**history-rewrite** (one coordinated cutover, any time).

**mcp-server** and **explain-parser** sit at the bottom deliberately:
both are shipped and awaiting Michael's hands-on testing before the items
are closed (or follow-ups get scheduled).
