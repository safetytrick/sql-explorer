# Purge sensitive content from git history

> **Status 2026-09-01: a cleaned copy exists at `~/mine/cleaned`.** Michael
> chose to produce a new repository rather than rewrite in place, which drops
> steps 4 and 5 below — no force-push, no coordination, no re-cloning. **The
> original repo and both remotes are unchanged and still carry everything
> described here**, so this plan stays until that is resolved one way or the
> other.

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

## What was actually done

`git-filter-repo` is not installed and there is no network to fetch it (see
`sqlx-dec`), so the rewrite used `git filter-branch --index-filter` — no
checkout per commit, which keeps 391 commits over 14 branches quick.

Two departures from the steps below, both deliberate:

- **The dump blob was replaced, not dropped.** Every version of
  `schemas/schema-dump.sql` becomes the one-newline placeholder the tip already
  carries. Dropping the path with `--invert-paths` would also have removed it
  from the tip and changed the current tree; replacing leaves the tip
  **byte-identical to the original** — verified by extracting both and diffing.
- **Three literal hosts, no wildcards.** A first pass used a wider pattern and
  rewrote `your.user@your-bastion-host` in `.env.example` — a placeholder, not a
  secret — which showed up immediately as a tip that no longer matched. The
  lesson is worth keeping: verify the tip is unchanged, because that is what
  catches an over-broad rule.

Classifying the candidates needed care. `bastion.<staging>.<company>.com` reads
like a placeholder because the line around it is `SSH_URL=your.user@…` — the
*username* is a placeholder and the *host* is real. Meanwhile `ssh-…` matched an
npm tarball name in a lockfile. Neither could be settled by pattern alone.

### Verified on the cleaned copy

- All three hosts: 0 occurrences in `git log --all -p`, 0 in any reachable tree,
  and 0 across all 2,672 text-sized blobs read with `git cat-file --batch`.
- Largest `schemas/schema-dump.sql` blob anywhere: **1 byte** (was 427,880).
- `8382c9d` no longer resolves; `git fsck` clean; 391 commits and 14 branches
  preserved; `.git` 53M → 6.5M.
- No remote configured, deliberately — where this gets pushed is a decision to
  make, not one to inherit.

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
