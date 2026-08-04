# Commands to run locally

## Post-commit cleanup (2026-08-04)

The session's remaining work is committed locally (5 commits, not
pushed). The sandbox can create/rename but not delete files, so the
committed deletions left their old copies behind as *untracked* files,
and git's stale locks/temp objects were renamed aside instead of
removed. One-time cleanup:

```bash
rm -r src/main/docker                          # deleted in the gradle commit
rm docs/plans/views-vs-tables.md docs/plans/parser-test-framework.md \
   docs/plans/secret-scrub.md docs/plans/normalizer.md \
   docs/plans/graph-generalization.md docs/plans/gradle-docker-cleanup.md \
   docs/plans/tag-widget-cleanup.md            # deleted in the docs commit
rm .git/dead-lock-*                            # stale locks moved aside
find .git/objects -name 'tmp_obj_*' -delete    # loose-object temp litter
git status                                     # should be clean (plus your .idea/)
```

## Material removal + non-root Docker user (2026-08-04)

`@angular/material` and `@angular/cdk` are removed from package.json —
`npm ci` prunes them and shrinks the install. New/updated Karma specs:
`tag-view.component.spec.ts` (chips, add/remove, dedupe, debounced
suggestions):

```bash
cd ui && npm ci && npm run test:ci
```

The backend image now runs as the non-root `sqlexplorer` user (uid 1001).
Docker isn't available in the sandbox — please build and smoke-test,
including a source load so the parquet sink write is exercised. On a
Linux host the bind-mounted `./src/main/docker-local/parquet` dir must be
writable by uid 1001 (`chown 1001` or `chmod g+w`); on macOS Docker
Desktop it works as-is:

```bash
docker compose build sql-explorer && docker compose up -d sql-explorer
docker exec sql-explorer id            # expect uid=1001(sqlexplorer)
docker compose logs -f sql-explorer    # watch a source load complete without permission errors
```

## Gradle docker-staging cleanup (2026-08-04)

The `dockerStage` task flow is gone from `build.gradle` (every jar build
stops paying for the staging copies). Sandbox can't delete files or run
gradle:

```bash
./gradlew assemble test               # gate — confirms the build file edit
docker compose build sql-explorer     # unchanged path, sanity only
```

(`src/main/docker/Dockerfile` is deleted in the commit itself — the
leftover untracked copy is covered by the cleanup section above.)

## Sidebar tag include/exclude (2026-08-04)

I could not find a broken line of code: the include/exclude wiring is
byte-identical to the pre-session working state at every layer (component,
QueryScopeService, workbench subscription, /tags/path endpoint), and the
whole filter chain — tags → schema.table needle → type toggle — now lives
in `tree-filter.ts` as `filterSchemaTree` with specs covering every
composition (all green under node). The one failure mode that produces
exactly "include shows nothing, exclude does nothing" is the schema-tags
request silently failing or returning mismatched keys, leaving the tag map
empty — that state is now logged loudly. Please retest with the console
open; if it persists, send me the console error and the response body of
`POST /api/sql-explorer/tags/path` from the network tab.

```bash
cd ui && npm run test:ci   # new specs: matchesTagFilter + filterSchemaTree suites
```

## Self-contained backend Docker build (2026-08-03)



---

*This file collects sandbox-blocked commands as work proceeds — new entries
get appended here rather than only mentioned in chat.*
