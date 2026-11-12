# Managing environments: what they are, and what they are missing

Written for `sqlx-v91`, which asks three questions:

> Do we attach them to a user session? Does that require that we add user auth
> first? Do we add a higher level concept to link environments in a
> dev-test-prod way?

This is the spike. Everything below is grounded in the code and in the two
environments the local compose stack actually runs.

## What an environment is today

`Environment` is a name and a list of `DatabaseServer`s whose SQL and schema are
analyzed together, built by `EnvironmentRegistry` from an `environments.*`
config block. It carries three settings: `default`, `explainEnabled` and
`pullCron`. There is no persistence — the registry is constructed at startup and
an environment cannot be created, edited or removed at runtime.

Selection is per request, and it is in the URL (sqlx-e13.6). Controllers
marked `@EnvironmentScoped` are served under `/env/{env}/`, and
`EnvironmentContext` (`@RequestScope`) reads that path variable; an unknown
name is a 404, not the default. (It used to be an `X-Sql-Explorer-Environment`
header with a silent fallback to the default, which authorization could not
read and a typo could not detect.) The frontend adds the segment to every
environment-scoped call in `environment.interceptor.ts`, taking the value from
`EnvironmentService.current$`, which is seeded from `localStorage` under
`sql-explorer.environment`.

So an environment is a **connection-scoped choice held in one browser's local
storage**. That single sentence is the source of all three findings below.

## The finding the bead does not ask about

**Every URL in the application is relative to a corpus the URL does not name.**

`docs/plans/url-parameter-vocabulary.md` derived the rule that decides what
belongs in a URL:

> A parameter travels if it names **objects in the corpus**. It stays if it is a
> **predicate over the rows this page happens to list**.

The environment is neither. It names **which corpus** — a category the
47-parameter inventory does not contain, because at the time nothing was asking.
That is worth stating plainly: the environment's absence from the URL is not a
violation of the "every view is a link" rule, which is about filtering and view
state. It is a gap the rule was never pointed at.

The consequence is real anyway. `/table/mysql/user` resolves against whichever
environment the recipient last picked. And this is not hypothetical — measured
on the running stack, `regression` and `local` share four schema names:

```
regression: app_admin app_auth app_core app_contacts app_main app_del … information_schema mysql … performance_schema … sys …
local:      explorer information_schema mysql performance_schema probe sys
```

`information_schema`, `mysql`, `performance_schema` and `sys` exist in both. A
link to any table in those four is silently ambiguous today, and a link to a
`app_main.*` table merely fails informatively — the recipient gets a not-found page
that names a real table, which is arguably worse than an error.

## Q1 — Do we attach environments to a user session?

**No.** Two reasons, and the second is the one that matters.

**It requires auth, and there is none.** `auth/TODO.md` says so outright: *"At
some point I had ambitions to add users and auth to this project, none of this
has actually been implemented."* `UserService.currentUser()` is
`userRepository.findByEmail("admin").orElseThrow()` — a hardcoded lookup with no
authentication behind it. There are no sessions to attach anything to. Building
them means an identity provider, session management, a `users` table that is
actually populated, and authorization rules, none of which improve what
environments do.

**It moves in the wrong direction.** A session-scoped environment is *less*
shareable than the localStorage one, not more: the link's meaning would then
depend on who opens it, with no way to state the intended corpus in the link
itself. The demand behind "attach them to a session" is almost certainly "stop
making me re-pick the environment" — and localStorage already does that. The
unmet need is the opposite one: being able to *say* which environment a link is
about.

Auth may be worth having for other reasons (`tags.author_id` and `notes.author_id`
exist and are meaningless today). It is not a prerequisite for anything in this
document, and environments should not be the argument for it.

## Q2 — Put the environment in the URL

This is the proposal. One cross-cutting parameter, `env`.

- **Not `var-` prefixed.** That prefix marks presentation a single page owns.
  The environment is the most cross-cutting value in the app.
- **Precedence: URL, then localStorage, then the backend default.** Opening a
  link with `?env=regression` shows regression regardless of what the recipient
  had stored.
- **Written always, including when it matches the default.** This deliberately
  breaks the omit-defaults rule, for exactly the reason `tagMode` breaks it: a
  bare URL is the ambiguous case, so the whole point is lost if the common
  environment is the one that stays unwritten. One short parameter is a cheap
  price for a link that means one thing.
- **An unknown `env` should say so.** `EnvironmentContext.getOrDefault` silently
  falls back today, which is right for a stale header and wrong for a URL a
  colleague just sent — it would show them a different corpus without a word.
  The frontend should surface "no such environment" rather than quietly
  substituting.

Cost is small: `EnvironmentService` seeds from and writes to the route, the
interceptor is unchanged, and the environment picker writes both the URL and
localStorage. The one design question is whether the parameter is re-applied
across navigations automatically or re-stated by each `routerLink` — the same
question `url-parameter-vocabulary.md` raised as its step 3, but much easier
here, because `env` is a single scalar rather than a family of five.

**Effort: S.**

## Q3 — A concept above environments, dev-test-prod

The bead's third question is the interesting one, and the evidence points
somewhere slightly different from where it points.

**All commentary is already shared across every environment.** The `tags` and
`notes` tables are keyed by `path` alone — `/schema/{s}`,
`/schema/{s}/table/{t}`, `/query/{digest}` — with no environment column, and
nothing in `commentary/` mentions environments at all. A tag applied while
looking at one environment is attached to every environment that has a schema of
that name.

Given the overlap measured above, **this is active, not latent**. And it is
active on precisely the schemas the product tells you to tag: the unqueried page
instructs *"Tag them and exclude that tag to subtract them"* about MySQL's own
schemas, which are four of the four shared names.

For those four, sharing is *correct*. `system` means the same thing in every
environment, and having to re-tag `performance_schema` per environment would be
a downgrade. The problem is that sharing is right for MySQL's own schemas and
wrong for application schemas, and today nothing can tell those apart.

So the answer is **yes, a higher-level concept is needed — but it is not a
grouping of environments. It is a scope on the commentary.** What a tag is about
is either:

- **an object that exists in several environments** because they run the same
  application (or because MySQL ships it), or
- **an object in one specific environment**.

Grouping environments dev-test-prod is one way to express the first, and it is
the expensive way. The cheap correct step is a **nullable `environment` column
on `tags` and `notes`, where null means "everywhere"** — which preserves the
meaning of every row that exists today, exactly, with no migration of data. A
tag can then be created either global or environment-specific, and the UI can
show which it is.

A named dev-test-prod grouping is an *optimization over that*: a set a tag can
be scoped to instead of one environment or all of them. It is worth building
only once the nullable column proves that "all" and "one" are too coarse. Do not
build it first — it is a schema, a config vocabulary, a UI, and a migration, in
service of a need that has not yet been demonstrated.

**Effort: M for the column, L for the grouping.**

> **Decided 2026-08-31, and built as `sqlx-rad`.** Michael chose the strict
> form over the nullable one: `environment_name` is NOT NULL, existing rows were
> backfilled to `regression`, and there is no everywhere-tag. The unique key on
> `tags` moved from `(path, name)` to `(environment_name, path(191), name)` —
> prefixed because the three columns at their declared widths are 4088 bytes of
> utf8mb4 against InnoDB's 3072-byte limit, and 191 is far beyond the longest
> path the application produces (74, a query digest).

## One behaviour nobody had decided — now decided

Observed on 2026-08-31 while working on `sqlx-qwn`, and met twice more the same
day: after a backend restart, `regression` was loaded and `local` was not. It
reported `loaded=false, sourceCount=0` until something pulled it, and in the
meantime its pages read as an empty corpus rather than as an unloaded one —
"0 analyzed", and an unqueried report claiming every table was untouched.

Nothing chose that. It followed from `regression` being the default.

**Decided 2026-09-01: load all.** Every configured environment loads at startup,
default first — the order matters because the resident cap
(`sql-explorer.resident-environments`, 3) evicts least-recently-used once
exceeded, so it decides what is still in heap when startup finishes. An evicted
environment is not lost; it rehydrates from the capture that load just wrote.

Two consequences worth knowing. Startup grew from 21s to 40s for two
environments on the local stack, and scales with the number configured. And no
environment's failure stops startup any more, which is a change: a load throws
when its server is unreachable, and that used to take the whole application down
from `@PostConstruct`. Booting and naming the environment that could not be read
is strictly more useful, and loading every environment makes an unreachable one
likelier.

## Recommendation

In order:

1. ~~**`env` in the URL** (S).~~ **Done** — `sqlx-29f`, as `?env=`. Since
   `sqlx-e13.7` it leads the path instead, `/prod/queries`, matching the API's
   `/env/{env}/` (`sqlx-e13.6`). The router never sees it:
   `EnvPathUrlSerializer` strips it on parse and writes the current one on
   serialize, so routes and links did not change. Old `?env=` links still
   open on their environment and are rewritten to the path form.
2. ~~**Decide the load-on-restart rule** (S).~~ **Done** — Michael's decision on
   2026-09-01 was *load all*. Every configured environment loads at startup,
   default first, and no environment's failure stops the others — previously a
   single unreachable server inside `@PostConstruct` took the whole application
   down, which only gets likelier once every environment is loaded. Measured
   cost on the local stack: startup went from 21s to 40s for two environments,
   so it scales with the number configured. Loading also *captures*, so a
   restart is now a sampling point for every environment rather than one.
3. ~~**An `environment_name` on commentary** (M).~~ **Done** — `sqlx-rad`.
   Michael's decision on 2026-08-31 went against the nullable form proposed
   above: the column is NOT NULL, every existing row was backfilled to
   `regression`, and there is deliberately no "everywhere" tag. The reasoning
   was that an everywhere-tag makes every reader ask which kind of tag they are
   looking at, in exchange for a convenience nobody had asked for. `tag_def`
   stayed global, so a tag's name and colour still mean one thing across the
   deployment while its associations do not.
4. **A dev-test-prod grouping** (L). Only if 3 proves "all or one" too coarse —
   and note that 3 as built is stricter than 3 as proposed, so the question is
   now whether per-environment alone is too coarse.

And explicitly **not** session-scoped environments, which need auth this project
does not have and would make links less shareable rather than more.
