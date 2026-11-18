#!/usr/bin/env bash
#
# Apply local changes to the running docker stack with the least work:
# rebuild only the side that changed, and recreate only the container that
# serves it, from host builds (docker-compose.local-build.yml).
#
# Which compose files make up *your* stack is personal (an RDS profile, the
# dev IdP, ...), so it is read from the git-ignored .env as COMPOSE_FILE,
# the variable docker compose itself reads, ':'-separated:
#
#   COMPOSE_FILE=docker-compose.yml:docker-compose.rds.yml:docker-compose.dev-idp.yml:docker-compose.local-build.yml
#
# docker-compose.local-build.yml is added if it is missing, since this script
# only ever runs host builds. Unset, the stack is docker-compose.yml alone.
#
# Usage:
#   ./dev-restart.sh              # auto: whatever is stale or not served
#   ./dev-restart.sh backend      # force the backend: build the jar, recreate
#   ./dev-restart.sh ui           # force the UI: build the bundle, recreate
#   ./dev-restart.sh all          # both
#   ./dev-restart.sh --dry-run    # say what auto would do, and do nothing
#
# Why the backend is stopped before its jar is built: the JVM loads classes
# from the jar lazily, so overwriting it under a running container breaks
# that process, down to its shutdown hooks (see CLAUDE.md, "Rebuilding the
# jar while the backend is running"). Stopping first lets it shut down
# cleanly while the old jar is still whole.
#
# Why the UI is recreated rather than restarted: `ng build` replaces its
# output directory, and a restarted container keeps the bind mount on the
# deleted one, serving the old bundle forever.
#
# Only sql-explorer and explorer-ui are ever recreated. Anything else in the
# stack (MySQL, the dev IdP) is started if it is down and otherwise left alone.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

MODE=auto
DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    auto|backend|ui|all) MODE="$arg" ;;
    --dry-run|-n) DRY_RUN=1 ;;
    -h|--help) sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $arg (try --help)" >&2; exit 2 ;;
  esac
done

LOCAL_BUILD=docker-compose.local-build.yml
JAR=build/libs/sql-explorer.jar
UI_OUT=ui/build/sql-explorer-ui/browser
BACKEND=sql-explorer
UI=explorer-ui
UI_URL=${UI_URL:-http://localhost:4200/}
START_TIMEOUT=${START_TIMEOUT:-240}

say() { printf '\033[1m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33m!!\033[0m %s\n' "$*" >&2; }

# ── the stack: COMPOSE_FILE from .env, plus the local-build overlay ──

compose_files=""
if [ -f .env ]; then
  # read the one variable; .env holds credentials and is not sourced
  compose_files=$(grep -E '^[[:space:]]*COMPOSE_FILE=' .env | tail -1 | sed -E 's/^[^=]*=//; s/^["'\'']//; s/["'\'']$//') || true
fi
[ -n "$compose_files" ] || compose_files=docker-compose.yml
case ":$compose_files:" in
  *":$LOCAL_BUILD:"*) ;;
  *) compose_files="$compose_files:$LOCAL_BUILD" ;;
esac

FILE_ARGS=()
WANTED_LABEL=""
IFS=':' read -r -a files <<< "$compose_files"
for file in "${files[@]}"; do
  [ -f "$file" ] || { echo "COMPOSE_FILE names $file, which does not exist" >&2; exit 2; }
  FILE_ARGS+=(-f "$file")
  WANTED_LABEL="${WANTED_LABEL:+$WANTED_LABEL,}$ROOT/$file"
done

dc() { docker compose "${FILE_ARGS[@]}" "$@"; }

running() { [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = true ]; }
config_files() { docker inspect -f '{{index .Config.Labels "com.docker.compose.project.config_files"}}' "$1" 2>/dev/null || true; }

# A file under these paths newer than the marker, or no marker: stale.
stale() {
  local marker=$1; shift
  [ -e "$marker" ] || return 0
  [ -n "$(find "$@" -newer "$marker" -type f -print -quit 2>/dev/null)" ]
}

served_bundle() { curl -fsS --max-time 5 "$UI_URL" 2>/dev/null | grep -o 'main-[A-Za-z0-9]*\.js' | head -1 || true; }
local_bundle() { ls "$UI_OUT"/main-*.js 2>/dev/null | head -1 | xargs -n1 basename 2>/dev/null || true; }

# ── what needs doing ──

build_backend=0; recreate_backend=0; build_ui=0; recreate_ui=0
why=()

if [ "$MODE" = backend ] || [ "$MODE" = all ]; then build_backend=1; recreate_backend=1; why+=("backend: asked for"); fi
if [ "$MODE" = ui ] || [ "$MODE" = all ]; then build_ui=1; recreate_ui=1; why+=("ui: asked for"); fi

if [ "$MODE" = auto ]; then
  # not src/main/docker-local: that is the running stack's config and captures, which it writes to
  if stale "$JAR" src/main/java src/main/resources models/src models/build.gradle build.gradle settings.gradle gradle.properties; then
    build_backend=1; recreate_backend=1; why+=("backend: sources newer than $JAR")
  elif ! running "$BACKEND"; then
    recreate_backend=1; why+=("backend: not running")
  elif [ "$(config_files "$BACKEND")" != "$WANTED_LABEL" ]; then
    recreate_backend=1; why+=("backend: started from other compose files ($(config_files "$BACKEND"))")
  fi
  if stale "$UI_OUT/index.html" ui/src ui/angular.json ui/package.json ui/package-lock.json; then
    build_ui=1; recreate_ui=1; why+=("ui: sources newer than its build")
  elif ! running "$UI"; then
    recreate_ui=1; why+=("ui: not running")
  elif [ "$(config_files "$UI")" != "$WANTED_LABEL" ]; then
    recreate_ui=1; why+=("ui: started from other compose files")
  elif [ "$(served_bundle)" != "$(local_bundle)" ]; then
    recreate_ui=1; why+=("ui: serving $(served_bundle), built $(local_bundle)")
  fi
fi

say "stack: ${compose_files//:/ + }"
if [ ${#why[@]} -eq 0 ]; then
  say "nothing to do: both containers run the current builds"
  exit 0
fi
for reason in "${why[@]}"; do echo "    $reason"; done
plan="build backend=$build_backend, recreate backend=$recreate_backend, build ui=$build_ui, recreate ui=$recreate_ui"
if [ "$DRY_RUN" = 1 ]; then say "dry run: $plan"; exit 0; fi

# ── the rest of the stack: started if down, never recreated ──

for service in $(dc config --services); do
  case "$service" in "$BACKEND"|"$UI") continue ;; esac
  if ! running "$service"; then
    say "starting $service"
    dc up -d --no-recreate "$service"
  fi
done

# ── backend ──

if [ "$build_backend" = 1 ]; then
  if running "$BACKEND"; then
    say "stopping $BACKEND before its jar is rebuilt"
    dc stop "$BACKEND"
  fi
  say "building the jar"
  if ! ./gradlew assemble -q; then
    warn "the backend build failed; starting $BACKEND again on the previous jar"
    dc up -d --no-deps "$BACKEND" || true
    exit 1
  fi
fi

if [ "$recreate_backend" = 1 ]; then
  say "recreating $BACKEND"
  dc up -d --force-recreate --no-deps "$BACKEND"
fi

# ── UI: built while the backend starts ──

if [ "$build_ui" = 1 ]; then
  say "building the UI"
  (cd ui && npm run build --silent) || { warn "the UI build failed; $UI left as it was"; recreate_ui=0; ui_failed=1; }
fi
if [ "$recreate_ui" = 1 ]; then
  say "recreating $UI"
  dc up -d --force-recreate --no-deps "$UI"
fi

# ── check what is now served ──

status=0
if [ "$recreate_backend" = 1 ]; then
  say "waiting for $BACKEND to start"
  since=$(docker inspect -f '{{.State.StartedAt}}' "$BACKEND")
  deadline=$((SECONDS + START_TIMEOUT))
  # grep reads every line rather than -q: an early exit would SIGPIPE docker logs and, under pipefail, read as no match
  until docker logs --since "$since" "$BACKEND" 2>&1 | grep 'Started .*Application' > /dev/null; do
    if ! running "$BACKEND"; then warn "$BACKEND exited"; docker logs --tail 40 "$BACKEND" >&2; status=1; break; fi
    if [ $SECONDS -ge $deadline ]; then warn "$BACKEND not started after ${START_TIMEOUT}s"; status=1; break; fi
    sleep 2
  done
  if [ $status = 0 ]; then
    docker logs --since "$since" "$BACKEND" 2>&1 | grep -o 'Started .*Application in [0-9.]* seconds' | tail -1 | sed 's/^/    /'
    # problems reaching databases surface as warnings, not a failed start
    problems=$(docker logs --since "$since" "$BACKEND" 2>&1 | grep -E ' (WARN|ERROR) ' | grep -cE 'could not be read|Failed to fetch token|expiration is in the past' || true)
    [ "$problems" = 0 ] || warn "$BACKEND logged $problems warnings about reaching its servers (expired credentials or connectivity); see docker logs $BACKEND"
  fi
fi
if [ "$recreate_ui" = 1 ]; then
  served=""
  for _ in 1 2 3 4 5 6 7 8 9 10; do served=$(served_bundle); [ -n "$served" ] && break; sleep 1; done
  if [ "$served" = "$(local_bundle)" ]; then
    echo "    $UI serves $served, the local build"
  else
    warn "$UI serves '${served:-nothing}', but the local build is $(local_bundle)"; status=1
  fi
fi
[ "${ui_failed:-0}" = 0 ] || status=1
exit $status
