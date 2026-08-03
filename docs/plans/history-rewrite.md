# Purge sensitive content from git history

Second half of "Clean all history so queries look anonymous". Prerequisite:
[secret-scrub.md](secret-scrub.md) — the tip must be clean before rewriting,
or the rewrite has to be redone.

## What's recoverable from history today

- **`schemas/schema-dump.sql`** — a 1-byte placeholder at tip, but commit
  `8382c9d` ("Clean commit with Example Config", 2023-11-14) added an
  **11,060-line real production mysqldump** whose header names a prior
  company's internal database host (MySQL 5.7.33). Deleted again in
  `002aaea`, so the blob is fully recoverable via
  `git show 8382c9d:schemas/schema-dump.sql`.
- **Staging hostnames** (current-employer bastion + RDS endpoint): introduced
  in `8382c9d` and `75371e8`; scrubbed from the tip on 2026-08-02
  (see [secret-scrub.md](secret-scrub.md)), still in history.
- Blast radius is small: 135 commits, single branch (`main`), only 2 commits
  touch each sensitive string (verified with `git log -S`).
- **Both remotes carry the history**: `origin` (GitHub `safetytrick/sql-explorer`)
  and `work` (GitLab `michael.nielson.go/sql-explorer`).

## Steps (in order)

1. Land the working-tree scrub (secret-scrub.md) and any in-flight branches.
2. Fresh mirror clone: `git clone --mirror git@github.com-mine:safetytrick/sql-explorer.git`.
3. Rewrite with `git filter-repo`:
   - `--invert-paths --path schemas/schema-dump.sql` (drop the blob entirely;
     re-add the placeholder in a follow-up commit if the docker mount at
     `docker-compose.yml:26-35` still wants the file), and
   - `--replace-text expressions.txt` mapping the RDS host, bastion host, and
     prior-company host strings to `REDACTED` equivalents (covers commit
     messages too; keep `expressions.txt` untracked — the exact strings are in
     the pre-scrub inventory / `git show 8382c9d:.env.example`).
4. Verify locally (see below), then force-push to **both** remotes and delete
   GitHub/GitLab cached views (contact support or re-create repos if strict;
   GitHub keeps unreachable objects reachable-by-SHA for a while).
5. Re-clone all working copies; old clones must not push again.

## Verification

- `git log --all -S <string>` → empty for each entry in `expressions.txt`
  (prior-company domain, RDS host, bastion host).
- `git rev-list --all | xargs -n1 git ls-tree -r | grep schema-dump` → only the
  placeholder (or nothing).
- `git cat-file -e <old-blob-sha>` fails after `git gc --prune=now --aggressive`.
- Run gitleaks (or the CI grep from secret-scrub.md guardrails) across
  `git log -p`.

## Open questions

- Force-push acceptable on both remotes? Any forks, other clones, or CI pinned
  to SHAs? (GitHub Actions in this repo reference branches, not SHAs — fine.)
- Is the GitLab `work` remote still needed at all?

## Effort: S — the rewrite is an hour; coordination and re-cloning dominate.
