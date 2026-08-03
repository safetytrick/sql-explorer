# Working-tree secret / identifying-content scrub

First half of "Clean all history so queries look anonymous". Companion:
[history-rewrite.md](history-rewrite.md) — do this scrub first so the tip is
clean before rewriting history.

> **Status: EXECUTED 2026-08-02.** The working-tree scrub below is done and the
> quoted strings in this doc have been redacted. The originals remain in git
> history until [history-rewrite.md](history-rewrite.md) runs.

## Inventory — tracked files with sensitive content (verified 2026-08-02)

### Must fix — DONE

1. **`.env.example:1-2`** — real staging infrastructure (an employer-domain
   bastion host and an AWS account-scoped RDS endpoint, both since replaced
   with `example.com` placeholders). Exposed: employer domain, RDS region +
   account suffix, bastion naming, DB topology (`db-master`).

2. **`docs/LOCAL_CONFIG.md`** — the whole file was employer-specific
   (name in the *filename*): the `<company>-staging-db-master` server key and
   the full SSH tunnel command with both the RDS endpoint and bastion host.
   Obsolete (superseded by `environments.*` config, docs/DATA_ENVIRONMENTS.md)
   — emptied to a tombstone; **needs `git rm`** (sandbox cannot delete files).

3. **`README.md:142,162`** — config examples used the company-prefixed server
   key; renamed to `staging-db-master`.

4. **`src/main/java/com/github/safetytrick/sqlexplorer/environment/EnvironmentsConfig.java:21`**
   — javadoc example used the same server key; renamed.

### Should fix (identifying, not secret) — DONE

5. **`slow-parse-*.sql`** (6 files) — each started with a real MySQL statement
   digest hash comment; replaced with `-- fixture: slow-parse-<n>`. The
   production-shaped vocabulary was renamed along with the schema fixtures.

6. **`src/test/resources/schema/*.sql`** (21 CREATE TABLE fixtures) — a
   coherent, identifiable domain vocabulary, renamed wholesale (see the
   mapping below) with files renamed to match; the 9 test classes referencing
   the vocabulary were updated in the same pass.

### Rename mapping applied (fragment-level, longest-first, case variants)

`correspondence→contact`, `notification/notif→alert`, `membership→plan`,
`sensitive/sensiti→special`, `customer→member`, `criteria→rule`,
`privacy→profile`, `tenant→org`, `visit→session`, `store→branch`,
`csr→agent`, `birth→signup`, `major→main`. Structure (statement shapes,
column counts, types, indexes, joins, hibernate-style aliases) preserved
exactly so the same parser paths are exercised.

### Checked and OK (no action)

- `.idea/runConfigurations/18185.xml` — tracked, but only a localhost debug
  config.
- `src/main/docker-local/config/application-secrets.yml`, `docker-compose.yml:33,43`,
  `src/main/docker-local/local.env` — `root`/`password` for the *local* Docker
  MySQL only; documented pattern, real overrides are gitignored
  (`application-override.yml`, `.env`).
- `.idea/workspace.xml` — mentions bastion in old commit-message task titles,
  but the file is untracked.
- `docs/TECH_DEBT.md:26,162-165` — *describes* this debt; update its status
  after the scrub rather than scrubbing it.

## Scrub steps (in order)

1. **`.env.example`** → placeholders:
   ```
   DB_HOST=your-db-host.example.com
   SSH_URL=your.user@your-bastion.example.com
   ```
2. **Replace `docs/LOCAL_CONFIG.md`** with a generic
   `docs/REMOTE_METADATA_SETUP.md` (same content, `staging-db-master` /
   `your-bastion.example.com` placeholders), update anything linking to it,
   and `git rm` the old file.
3. **`README.md:142,162` + `EnvironmentsConfig.java:21`** → rename the example
   server key to `staging-db-master`.
4. **`slow-parse-*.sql` headers** → replace the six real digest hashes with
   `-- fixture: slow-parse-<n>` comments. Verify nothing keys on them
   (`git grep 419d7d9e` etc. — only the fixture files themselves; the filenames
   already use 3-char prefixes, keep those).
5. **Fixture vocabulary (optional, larger)** — rename tables/columns in
   `src/test/resources/schema/*.sql` and `slow-parse-*.sql` to the established
   `foo_*`/`some_*` style. Must be done consistently: analyzer tests assert
   specific names (`TestSchemas.of("store", …)`, assertions in
   `SqlAnalysisServiceTest` etc.), so this is a coordinated rename across
   fixtures + ~6 test classes, preserving structure (column counts, types,
   index shapes) so the same parser paths are exercised. Budget this
   separately (M) and decide via the open question below.
6. **Guardrails** — add a CI step (extend `.github/workflows/ci.yml`) running
   gitleaks or a simple grep denylist (the employer domain, the prior-company
   domain, `rds.amazonaws.com`, `bastion.` — keep the exact strings in an
   untracked `scrub-denylist.txt`) against the tree; note the fixture
   vocabulary convention in a CONTRIBUTING section.
7. **Afterwards**: update `docs/TECH_DEBT.md` top-priority #5 status, redact
   this file's Inventory section (or keep plans uncommitted), then proceed to
   [history-rewrite.md](history-rewrite.md).

## Verification

- `git grep -i -E -f scrub-denylist.txt` (untracked file with the employer
  domain, prior-company domain, RDS account suffix, `rds\.amazonaws`,
  `bastion\.`) → no hits anywhere in the tree.
- `git grep -i <employer>` → no hits outside TECH_DEBT status notes.
- For each replaced digest hash: `git grep <hash>` → empty.
- `./gradlew test` and `cd ui && npm run lint` still green (steps 3–5 touch a
  javadoc and test fixtures).
- Re-run the same greps against `git log -p -5` to confirm the scrub commits
  themselves don't quote the removed strings in their messages.

## Open questions

- Fixture vocabulary rename (step 5): worth the churn now, or defer until the
  parser-test-framework work (which will touch the same files) lands?
- Keep the `docs/LOCAL_CONFIG.md` content at all, or is that setup
  obsolete now that `environments.*` config exists (`docs/DATA_ENVIRONMENTS.md`)?

## Effort: S (an hour) for steps 1–4, 6–7; M if step 5 is included.
