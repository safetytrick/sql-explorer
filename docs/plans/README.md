# Implementation Plans

One plan per WORK_TODO item, grounded in code inspection (2026-08-02).
Effort scale: S = hours, M = 1–3 days, L = a week+.

| Plan | Item | Effort |
|---|---|---|
| [secret-scrub.md](secret-scrub.md) | Scrub secrets/identifying content from the working tree | S |
| [history-rewrite.md](history-rewrite.md) | Purge sensitive content from git history | S + coordination |
| [views-vs-tables.md](views-vs-tables.md) | Distinguish between views and tables | S–M |
| [parser-test-framework.md](parser-test-framework.md) | Parser test framework accepting schema as SQL | S–M |
| [normalizer.md](normalizer.md) | Normalize repeated SQL blocks before hashing | M |
| [normalization-versioning.md](normalization-versioning.md) | Versioning/evolution of the normalization scheme | S–M |
| [graph-generalization.md](graph-generalization.md) | Graph UI on any query/tag result | M |
| [explain-parser.md](explain-parser.md) | Improve the explain plan parser | S–M |
| [mcp-server.md](mcp-server.md) | Add an MCP server | M |
| [legacy-ui.md](legacy-ui.md) | Update legacy UI pages to new formatting | M–L |

## Suggested order

Cheap unlocks first: **secret scrub** (independent, overdue), **views-vs-tables
step 1** (view badge — data already flows), **parser-test-framework steps 1–2**
(ergonomics pay for every later parser change). Then **normalizer** (while the
test framework is fresh), **graph-generalization**, **explain-parser**,
**mcp-server** (after the `getAllAnalyzedSql()` perf debt), and
**legacy-ui** + **history-rewrite** as coordinated cleanups.
