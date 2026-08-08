# Commands to run locally

## MCP tag writes (2026-08-07)

`add_tag` / `remove_tag` shipped — 18 tools now, and the server is no
longer read-only. Verified here (real stdio transport against a stubbed
backend, including the tag-write request shapes):

```bash
cd mcp && npm test
```

Manual smoke, against a running instance from a stdio client: tag a
query by its `digest` (not `id` — the UI hangs query tags off the
digest, so a tag on anything else stays invisible there), then confirm
the chip shows up in the workbench.

## Collapsed-repeat badges over the wire (2026-08-07)

The `RepeatedBlock` Jackson mixin fix. Verified here — `./gradlew test`
is green (186 tests, 8 skipped, 0 failures) including the new
`RepeatedBlockSerializationTest`:

```bash
./gradlew test --tests 'com.github.safetytrick.sqlexplorer.model.RepeatedBlockSerializationTest'
```

Note `./gradlew test` alone reports `UP-TO-DATE` and runs nothing once a
previous run cached; use `./gradlew cleanTest test` when you want to see
the suite actually execute.

Manual smoke: open a query in the workbench whose SQL had repeated
blocks collapsed — the "collapsed repeats" chips should render, and the
whole `/queries/search` response should arrive intact (the old failure
truncated the entire payload, not just the field).

## Shareable search-scoped graph links (2026-08-04)

The whole workbench search — including schema-tree selections and tag
filters — now travels in the URL, with identical param names on
`/queries` and `/graph?source=search`. Specs: `search-url.spec.ts`
(codec + tag resolution + request assembly) and
`queries-workbench.component.spec.ts` (RouterTestingHarness seeding).
Both **now run green in a real browser here** — 95/95, and `npm run
lint` is clean (6 pre-existing style warnings, 0 errors):

```bash
cd ui && npm run test:ci
```

**Gotcha worth knowing before writing new specs:** `jasmine.objectContaining`
is broken under this Karma/Jasmine build — it throws
`ReferenceError: i is not defined` with a stack that points only at
`<Jasmine>`, even in a bare `createSpy` test with no Angular involved.
Assert against `spy.calls.mostRecent().args` with plain `toEqual`
instead; that also reads stronger, since it catches params leaking into
a link rather than only the ones you named.

Manual smoke:

1. On Queries: pick tables/schemas in the sidebar, add a schema tag
   include, set a filter or two → the URL should carry `tables=`,
   `schemas=`, `schemaTags=`, etc. Reload → everything, including the
   tree checkboxes and tag chips, comes back.
2. Click "graph results" → the graph URL carries the same params; copy it
   into another window (or profile) → same graph. Layer toggles/selection
   on that page must not drop the search params from the URL.
3. Old bookmarks: `/graph/<schema>?table=x` still highlights.

## Graph URL state (2026-08-04)

Graph selection + layer toggles now round-trip through `var-table` /
`var-layers` query params (legacy `?table=` links still work). New Karma
spec: `graph-url.spec.ts` (encode/decode, defaults, legacy fallback,
round-trips):

```bash
cd ui && npm run test:ci
```

Manual smoke: on `/graph/<schema>`, toggle a layer and click a node —
the URL should update in place (no history spam); reload/share it and the
same selection + layers should come back. Click the selected node again
to open its table page. Back/forward should replay selection changes
without refetching the graph.

---

*This file collects sandbox-blocked commands as work proceeds — new entries
get appended here rather than only mentioned in chat.*
