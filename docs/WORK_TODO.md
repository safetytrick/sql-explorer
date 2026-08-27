# Open work has moved to beads

This file is a signpost, not a list. Open work lives in the beads database in
`.beads/`, queried with the `bd` CLI:

```bash
bd ready                  # unblocked work, highest priority first
bd list --status open     # everything still open
bd show sqlx-1cm          # one item, with its full description
bd dep tree sqlx-1cm      # what it blocks and what blocks it
```

Every item that was in this file on 2026-08-27 is in there with its prose
intact, under the `sqlx-` prefix. The git history of this file is still the
record of how those items were worded before the move.

Why: the items had grown cross-references that markdown could not hold —
"the mirror of the bug fixed in `f1f53d2`", "blocked on a decision", "shipped,
awaiting verification" — and positional numbering that broke whenever an item
was inserted or closed. Those are dependency edges, states and stable ids, and
beads has all three.

`TECH_DEBT.md` stays as it was: the raw standing inventory, not a work queue.
`plans/` still holds the longer design write-ups, now referenced by bead id.
