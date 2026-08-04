# Cleanup gradle build (remove the old docker staging flow)

> **Status: implemented 2026-08-04.** `dockerSourceCopy`/`dockerLibCopy`/
> `dockerStage` and the `jar.finalizedBy` hook removed from `build.gradle`
> (braces verified balanced); `src/main/docker/Dockerfile` tombstoned
> pending `git rm`. Reference sweep found no CI usage; the TECH_DEBT
> root-user hardening note was repointed at the root Dockerfile's runtime
> stage (which also runs as root — the debt survives the cleanup). Gate:
> `./gradlew assemble test` locally.

## Current state

The repo-root `Dockerfile` (self-contained two-stage build, compose
`build: .`) replaced the host-gradle staging flow, which is now dead code:

- `build.gradle:62-79` — `dockerSourceCopy`, `dockerLibCopy`, and
  `dockerStage` Copy tasks that assemble `build/docker/` from
  `src/main/docker` + `build/libs`.
- `build.gradle:79` — `jar.finalizedBy(dockerStage)`: **every** jar build
  (including plain `./gradlew test` runs that build the jar) pays for the
  staging copies.
- `src/main/docker/Dockerfile` — the runtime-only Dockerfile the staging
  flow copied into `build/docker`; nothing references it anymore
  (docker-compose builds from the repo root).

## Proposed approach

Delete the three task registrations and the `finalizedBy` hook from
`build.gradle`, and `git rm src/main/docker/Dockerfile` (removing the
directory if empty). Grep for `dockerStage`/`build/docker` in CI configs and
docs before deleting — `README.md` was already updated; check
`.github/workflows/` and `docs/local-dev-mysql.md`. No test impact: the
tasks only copy files.

## Open questions

- None of substance — this is deletion. Only check: does anything on your
  machine (IDE run configs, scripts) invoke `dockerStage` directly?

## Effort: S (minutes).
