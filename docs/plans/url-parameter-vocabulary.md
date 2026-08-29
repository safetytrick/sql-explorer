# The URL parameter vocabulary: an inventory, and what should travel

Written for `sqlx-uxo`, whose second half is "a scope that survives
navigation". A first attempt proposed a rule — *exclusion travels, inclusion
does not* — that was fitted to one example rather than derived from anything.
Michael pushed back; this is the inventory that should have come first.

## The inventory

47 distinct query parameters, generated from the encoders rather than
transcribed, grouped by **what the parameter is a statement about**.

### 1. Presentation — how this page draws itself

| param | page | |
|---|---|---|
| `var-layers` | `/graph` | which edge kinds render |
| `var-table` | `/graph` | selected node |
| `var-type` | `/tables` | all / tables / views |
| `sort` | `/queries` | result ordering |

### 2. Question — what this page is being asked

No other page can ask these. 30 of the 47.

| param | |
|---|---|
| `q`, `activity`, `queryTypes` | text, activity, statement kind |
| `firstAfter` `firstBefore` `lastAfter` `lastBefore` | date-math bounds |
| `avgTime{Min,Max}` `maxTime{Min,Max}` `avgRows{Min,Max}` `sel{Min,Max}` `sorted{Min,Max}` | 10 range bounds |
| `fullScan` `tmpTable` `tmpDisk` `errors` `warnings` `truncated` `multiSchema` | 7 tri-state execution flags |
| `analWarn` `analFail` `analClean` `analSkipped` `failKind` `hasExamples` | analysis state |

### 3. Drill-down — a specific thing being examined

| param | page | |
|---|---|---|
| `columns` | `/queries` | queries touching these columns |

### 4. Scope — which part of the corpus is in play

| param | page | |
|---|---|---|
| `schemas` | `/queries` | scope-tree schema selection |
| `tables` | `/queries` | scope-tree table selection |
| `tableMode` | `/queries` | ANY / ALL over those tables |
| `schemaTags` | `/queries` | include schemas tagged … |
| `schemaTagsExclude` | `/queries` | **exclude** schemas tagged … |
| `schemaTagMode` | `/queries` | ANY / ALL over those |
| `tag` | `/graph` | graph the queries carrying this tag |
| `source` | `/graph` | graph the workbench search |

### 5. Row filter — "of the things listed here, keep those tagged …"

| param | pages | |
|---|---|---|
| `tags` | `/queries` `/tables` `/schemas` | **shared name, different subject** |
| `tagMode` | `/queries` `/tables` `/schemas` | **shared name, different default** |
| `tagsExclude` | `/tables` `/schemas` | shipped 2026-08-29 |
| `untagged` | `/queries` | carries no tag at all |

## Two inconsistencies, neither of them about travel

**The same intent has two spellings.** "Hide the system schemas" is
`schemaTagsExclude=system` on `/queries` and `tagsExclude=system` on
`/tables`. Same stance, same objects, two parameters, two codecs, two
components, and neither reaches the other. I added the second one earlier the
same day without noticing it duplicated the first. That is the actual defect,
and no amount of parameter-carrying fixes it — it would just carry the
duplication around.

**One name, two meanings.** `tags` and `tagMode` are the only names shared
across pages. On `/queries` they filter queries; on `/tables` and `/schemas`
they filter tables and schemas — and they carry *different defaults*, ANY
versus ALL, a divergence CLAUDE.md documents deliberately so a bare `?tags=a,b`
cannot be misread. That workaround is a sign the vocabulary was already under
strain before anything was asked to travel.

## Why the first proposal was wrong

It tried to make a **row filter** travel. A row filter is defined relative to
what the current page lists, so carrying it forward changes its subject on
arrival — which is precisely the collision above. Exclusion-travels-inclusion-
does-not was a plausible-sounding rule that would have hard-coded that
collision into the router.

The thing that should travel is not a filter at all. It is a **scope**, and the
app already has one — `schemas`, `tables`, `schemaTags*` — it simply does not
travel and is not reachable from any page except `/queries`.

## A rule that is derived rather than fitted

> A parameter travels if it names **objects in the corpus**.
> It stays if it is a **predicate over the rows this page happens to list**.

Applied to the inventory, this decides every one of the 47 without a special
case:

- **Group 4 travels.** Every member names schemas, tables, or tags attached to
  them. The statement is identical on any page.
- **Groups 1, 2, 3 and 5 stay.** Each is defined relative to this page — how it
  draws, what it asks, what is selected in it, what it lists.

Two members of group 4 need a caveat: `tag` and `source` on `/graph` are scope
*selectors* — they choose what the graph **is**, not a background stance held
while you do something else. They belong to the page in practice. Either
rename them into the presentation group, or treat "travels" as applying to the
scope-tree family only. The second is less churn.

## What this implies for `sqlx-uxo`

Not "make `tagsExclude` travel". Rather:

1. **Unify the two exclusions.** One scope vocabulary, one codec, used by the
   workbench scope tree and both list pages. This partly reverses what shipped
   on 2026-08-29 — `tagsExclude` becomes a view onto the scope rather than its
   own page-local filter.
2. **Give every page the scope control**, not just `/queries`.
3. **Carry the scope family across navigations** with a single route guard, so
   no `routerLink` has to remember. The guard must return `true` when the
   params are already present, or every navigation re-navigates forever.
4. **Leave the row filters exactly as they are.** They are page-local and
   correct; `tags`/`tagMode` keep their per-page defaults.

## The alternative worth weighing

Step 3 is the expensive and magical one. Steps 1 and 2 alone deliver most of
the value: one vocabulary, available everywhere, still explicit in each page's
URL. You would re-apply the scope when you move pages — which is the original
complaint, but the complaint is much smaller once the control is one click on
every page instead of absent from four of them.

Recommendation: do 1 and 2, measure whether 3 is still wanted.
