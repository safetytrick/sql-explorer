# Does the UI serve the work it is for?

An evaluation of the app against the optimization workflows it actually
supports, done under `sqlx-cs2`. The design canvas is the readable form of
this — five artboards, published at

  https://claude.ai/code/artifact/123cd2af-f18c-47c1-a410-7db87a77245c

with sources in `.design/cs2/*.dc.html`. This file is the durable copy: the
findings, what they were verified against, and which beads came out of them.
The published page is 2.5 MB (it embeds its own editor), which is why it is
not committed.

## Method, and why it matters here

The workflow list was derived by walking the pages, not from what a query
tool is generally expected to do. A list written the other way round grades
the app on what it already does well, and would have missed both of the
findings that turned out to matter.

Corpus figures throughout are from the live regression environment as of
2026-08-29: 4,301 profiles, 1,802 of which skipped analysis, 68 merged
groups, 684 in-schema tables, 373 of them with no query references.

## The finding in one line

**The app is excellent at evidence and has nothing for judgement**, and it is
weak at the step before evidence — choosing what you are looking at.

The slow-query journey has six steps. Steps 1–3 — find it, cost it, ask why —
are genuinely strong, and impact-first ordering plus index scoring against
*this* query's own columns are the distinctive parts. Step 0 (narrow to what
is yours) and steps 4–5 (confirm, then decide) are where it thins out.

## What is well served

Not a courtesy list. These are the standard the rest is measured against and
the parts a redesign is most likely to lose while improving something else:

- **Impact-first ordering** on `/queries`, so the default view answers the
  question without configuring anything.
- **Tri-state flags** (`any` / `yes` / `no`) — answers "show me what is *not*
  a full scan", which a checkbox cannot.
- **Index scoring against the query's own criteria and join columns**, rather
  than listing every index on the table. The single most valuable thing on
  the SQL view.
- **Column roles** (criteria / join / select) on the table lens, which turn a
  column list into a diagnosis.
- **URL-backed everything**, so a finding is sendable.
- **Tag exclusion in the scope tree** — the only "everything except" control
  in the app, and the right idea.

## Finding 1 — nothing says how much of the corpus you are seeing

1,802 of 4,301 profiles skipped analysis: 42%. Every count on every page is
drawn from the remainder and none of them says so. A table lens column
showing no criteria use may simply never have been analysed, and the page
renders that identically to "analysed, and not used as criteria".

This is the same failure the table lens already fixed for one case — it
stopped making confident affirmative statements about tables it had no data
for (see the memory note on `/metadata/table/**` returning a null body). The
rest of the app still makes them.

→ `sqlx-3e2`

## Finding 2 — focus does not travel

Raised by Michael reviewing the first draft, and it reframed the audit.

Focus is **task-scoped, not object-scoped**. A `system` tag over
`information_schema`, `mysql`, `sys`, `performance_schema` does not mean
*hide these*; it means *not while I am hunting index opportunities* — the
same tables are wanted when drafting a query against them. Nothing in the app
hides unconditionally, which is the right instinct. The problem is that the
control to say so exists in one place.

Where exclusion exists, read off the templates and the URL codecs:

| Surface                | Include by tag | Exclude by tag | Spelled |
|------------------------|----------------|----------------|---------|
| `/queries` scope tree  | yes            | **yes**        | `?schemaTags=` / `?schemaTagsExclude=` |
| `/graph?source=search` | inherited      | inherited      | carries the workbench params |
| `/tables`              | yes            | no             | `?tags=` + `tagMode=ALL\|ANY` |
| `/schemas`             | yes            | no             | `?tags=` + `tagMode=ALL\|ANY` |
| `/table/:schema/:name` | —              | no             | — |
| `/analyzed-sql/:digest`| —              | no             | — |

Two consequences:

- **It does not survive navigation.** `QueryScopeService` has exactly two
  consumers — `schema-tree` and `queries-workbench`. Exclude the system
  schemas on `/queries`, click through to `/tables`, and they are back.
- **The gap is worst where the need is most obvious.** `/tables` is the page
  you land on to survey a schema and has include-only — and its neighbouring
  all/tables/views control is not URL-backed either (already noted in
  CLAUDE.md). Two focus affordances missing from the same control strip.

The design constraint worth stating up front: CLAUDE.md calls a filter that
lives only in component state a bug. So a working scope that survives
navigation has to ride in each page's URL, or the rule needs an explicit
exception. It should not quietly become sticky global state.

→ `sqlx-uxo`

## Finding 3 — the scoped graph does not show its scope

Building a graph from a tagged set *is* creating a focused context, and it is
the app's best focus mechanism. Its toolbar undercuts it three ways
(`graph-lens.component.html`, `graph-lens.component.ts`):

- **The schema `<select>` renders in every mode and always shows a schema
  name.** `loadSchemas()` does `if (!this.schema && …) this.schema =
  schemaNames[0]` with no mode check, so landing on `/graph?tag=system` fills
  it with the first schema alphabetically. Two labels in one toolbar and one
  of them describes nothing on screen.
- **Changing it discards the scope silently.** `onSchemaChange()` navigates
  with `tag: null, source: null`, and in search mode `encodeGraphParams`
  emits only the `var-` params so the search payload goes too. The control
  that reads as *narrow further* is *discard the working set*. That it is a
  slip rather than a decision is clear from the rest of the file: selection
  and layer writes deliberately use a merge patch so co-resident search
  params survive.
- **Search mode names the mechanism, not the scope.** `workbench results`
  says how the graph was built, not what is in it. Tag mode already does
  better one branch up, rendering `tag: <name>`.

There is also no non-destructive exit: the chip is a label, no `×`, and with
`replaceUrl` churn on every selection, back is not a dependable undo.

→ `sqlx-p7u`

## Finding 4 — no path from evidence to decision

The journey ends at step 4. Nothing records that a query was examined and
judged acceptable, so the next person redoes the first four steps to reach
the same conclusion. Tags are the nearest thing and were not designed for it.

The same hole shows from the other side in the tag vocabulary, which is
carrying three jobs with incompatible lifetimes:

- **Scope** — `system`, `clickhouse`. Long-lived, membership churns freely,
  mostly used to subtract.
- **Selection** — the queries currently under investigation. Short-lived,
  disposable, graphable.
- **Reference** — a link pasted into a ticket as the record of a decision.
  Wants to still mean the same thing in six months.

The collision is in the repo's own words. CLAUDE.md is explicit that a shared
link carries *intent*, not resolved results: a tag filter travels as the tag
name and is re-resolved against live data, because "a snapshot of what the
tag matched at share time would go stale." That is right for scope and
selection and exactly backwards for reference — a link in a ticket *is* a
decision record, and re-resolution silently rewrites what the decision was
made against. The app has one mechanism and cannot tell the two apart.

Whether the answer is tag kinds, saved views, or a separate record type is a
design question of its own. This audit claims only that the three jobs exist
and that one mechanism serves all of them.

→ `sqlx-nrz`

## Correction — `sqlx-m0x` overstates the graph gap

The bead says by-tag graphs "have no lens equivalent at all". They do:

- **Lens** `/graph?tag=` graphs the *queries* carrying a tag
  (`graph/v2/by-tag`), reachable from the tag manager's `graph ↗`. And
  `?source=search` graphs an entire workbench search, which is strictly more
  expressive than a tag.
- **Legacy** `/table-graph/by-tag/:tag` seeds from the *tables* carrying the
  tag, then walks outward `depth` hops.

So the tagged-queries graph is already in the lens, and better. The real
remainder is narrower: **depth expansion from a seed set** ("my working set
plus one hop", which is how you find out what else a change touches),
single-table focus, and cluster rendering. `var-layers` toggles which edge
kinds draw; it does not walk outward.

Worth re-reading before the deferral stands, since m0x also gates dropping
`@swimlane/ngx-graph` and the `legacy-peer-deps` flag (`sqlx-ila`).

## Already tracked

- `sqlx-6v1` — merged groups can span an `IN` of 3 and an `IN` of 1000 and
  report an average that describes neither.
- `sqlx-66m` — unqueried tables. 79% of the 373 is MySQL's own system
  schemas, which makes it the same problem as findings 2 and 3: readable only
  once exclusion is available where the report lives.
- `sqlx-vrz` — live EXPLAIN needs a stored example and an opted-in
  environment; neither precondition is visible until the button fails.
