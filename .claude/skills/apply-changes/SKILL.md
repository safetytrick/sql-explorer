---
name: apply-changes
description: Apply local code changes to the running docker stack with the minimal rebuild and restart, keeping this machine's compose configuration (RDS, dev IdP, ...). Use when the user asks to restart, redeploy, rebuild the containers, "apply my changes", see a change in the running app at localhost:4200, or when the running app may be serving stale code.
---

# Apply changes to the running stack

`./dev-restart.sh` at the repo root does the work. It reads the stack's compose
files from `COMPOSE_FILE` in the git-ignored `.env`, adds
`docker-compose.local-build.yml`, and then rebuilds and recreates only what is
stale:

- **Backend:** if sources under `src/main/java`, `src/main/resources`,
  `models/src` or the Gradle files are newer than `build/libs/sql-explorer.jar`.
  It stops the container *before* `./gradlew assemble`, because overwriting the
  jar under a live JVM breaks that process. Then it recreates the container and
  waits for `Started …Application`.
- **UI:** if `ui/src` or the npm/Angular config is newer than `ui/build`. It runs
  `npm run build` and recreates `explorer-ui`, because a restart keeps serving the
  old bundle. Then it checks that the served `main-*.js` matches the local one.
- **No rebuild:** a container that is down, or that was started from other
  compose files, is recreated without one.
- **Other services** (MySQL, dev IdP) are started if they are down and are never
  recreated.

## Steps

1. Run `./dev-restart.sh --dry-run` and read the plan. If it says "nothing to
   do", tell the user the running app is already current, and stop there.
2. Run `./dev-restart.sh`. Give it up to 10 minutes; the jar build is the slow
   part. Force a side only when the user asks, or when you know auto-detection
   can't see the change:
   - `./dev-restart.sh backend`, `./dev-restart.sh ui` or `./dev-restart.sh all`;
   - for example, after a `git checkout` that gave sources older timestamps than
     the build.
3. Report what was rebuilt and recreated, from the script's output.

## Reading the result

- **Exit 0** means the recreated containers run the new builds.
- **"logged N warnings about reaching its servers"** means the backend started,
  but its databases could not be read. It is then serving the environment from
  its cache. Usually the AWS SSO session has expired; check `docker logs
  sql-explorer` for `expiration is in the past`. The fix is the user's:
  `! aws sso login --profile <profile>`, then a pull from the nav. Do not retry
  the restart for this.
- **"the backend build failed"**: the script started the backend again on the
  previous jar. Show the compile error and fix it before running again.
- **"serves X, but the local build is Y"**: the UI container did not pick up the
  new build. Run `./dev-restart.sh ui` once more. If that doesn't fix it, compare
  `curl -s localhost:4200/ | grep -o 'main-[A-Z0-9]*\.js'` with
  `ls ui/build/sql-explorer-ui/browser/main*.js`.
- **"COMPOSE_FILE names …, which does not exist"**, or a stack line missing a
  file the user expects: `.env` is the user's to change. Ask before editing it;
  it also holds credentials, so never print it whole.

## Don't

- Don't run `./gradlew build` or `assemble` against a running backend outside
  this script. If you need the gate's `./gradlew build`, run it, then run
  `./dev-restart.sh`; the script rebuilds the jar and restarts from it.
- Don't run `docker compose restart` or `docker compose up` with a hand-picked
  `-f` list. `COMPOSE_FILE` in `.env` is the source of truth for which files make
  up this stack.
