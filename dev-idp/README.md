# dev-idp: a dummy OIDC provider

A Spring Authorization Server with canned users, one per role, for development
and e2e (sqlx-e13.3). It is not part of sql-explorer and must never be
deployed: the passwords are the usernames. Users, clients and sessions are in
memory. The signing key is generated on first start and saved, so tokens stay
valid across restarts: `dev-idp/.data/signing-key.json` under bootRun
(git-ignored), the `dev-idp-data` volume in Docker. Delete it to rotate.

## Start it

```bash
docker compose -f docker-compose.yml -f docker-compose.dev-idp.yml up -d dev-idp
```

Or without Docker:

```bash
./gradlew :dev-idp:bootRun
```

Discovery is at <http://localhost:18190/.well-known/openid-configuration>.

## Signing in to sql-explorer with it

sql-explorer signs in through this IdP under the `dev-idp` profile, which sets
`sql-explorer.auth.mode: oidc` and the client
([`application-dev-idp.yml`](../src/main/docker-local/config/application-dev-idp.yml)).
Without it the app runs with sign-in off (`mode: none`), as the legacy
`admin` user, and the UI shows a "sign-in not configured" banner.

In Docker, the overlay starts the IdP and turns the profile on for the backend:

```bash
./gradlew assemble && (cd ui && npm run build)
docker compose -f docker-compose.yml -f docker-compose.local-build.yml -f docker-compose.dev-idp.yml up -d
docker compose -f docker-compose.yml -f docker-compose.local-build.yml -f docker-compose.dev-idp.yml up -d --force-recreate explorer-ui
```

Then open <http://localhost:4200>, which sends you to the IdP's login form;
sign in as, say, `viewer` / `viewer`. The nav shows who you are and has
"Sign out", which also ends the IdP session (RP-initiated logout).
`/api/sql-explorer/user` shows the `groups` claim as received.

On the host, start the IdP with `./gradlew :dev-idp:bootRun` and run the
backend with `-Dspring.config.additional-location=src/main/docker-local/config/
-Dspring.profiles.include=local,dev-idp` (port 18180). Every endpoint is
`localhost` then. There is no UI on 18180, so sign in at
<http://localhost:18180/api/sql-explorer/login?return=/api/sql-explorer/user>
to land on the result.

## Logins

The password is the username.

| Login      | Groups | What it is for |
|------------|--------|----------------|
| `viewer`   | `sqlx:viewer:local` | Viewer on `local` only; `production` is not listed |
| `runner`   | `sqlx:viewer:local`, `sqlx:runner:local/app` | Runner on one datasource; no EXPLAIN |
| `author`   | `sqlx:viewer:local`, `sqlx:author:local/app` | Author on the same datasource |
| `mixed`    | `sqlx:viewer:local`, `sqlx:viewer:production`, `sqlx:runner:local`, `sqlx:author:production/app` | Different roles in different places; EXPLAIN on `local` |
| `admin`    | `sqlx:admin` | Admin everywhere, including pulls |
| `nogroups` | (none, an empty list) | Signed in but granted nothing |

They are defined in
[`DevUsers.java`](src/main/java/com/github/safetytrick/sqlexplorer/devidp/DevUsers.java).

## Access tokens and MCP

With sign-in on, MCP and API clients use a personal access token
(sqlx-e13.5). To try one end to end:

1. Sign in at <http://localhost:4200> as, say, `admin` / `admin`.
2. Click **Tokens** beside your name in the nav (or open `/tokens`), then
   **New token**. Name it, tick a ceiling, e.g. Viewer under `local`, and make
   it. Copy the `sqlx_…` secret: it is shown once.
3. `export SQL_EXPLORER_TOKEN=sqlx_…`, then register the endpoint as the MCP
   page (Admin → MCP) shows, which sends `Authorization: Bearer
   $SQL_EXPLORER_TOKEN`:

   ```bash
   claude mcp add --transport http sql-explorer http://localhost:4200/api/sql-explorer/mcp \
     --header 'X-Sql-Explorer-Environment: local' \
     --header "Authorization: Bearer $SQL_EXPLORER_TOKEN"
   ```

4. Or by hand: `curl -H "Authorization: Bearer $SQL_EXPLORER_TOKEN"
   localhost:18180/api/sql-explorer/user` shows the token's effective grants;
   a Viewer token from `admin` gets a 403 from
   `POST /api/sql-explorer/environments/local/pull`.

A token may do what both its ceiling and its owner may. The owner's access is
taken from the groups at their **latest sign-in**: tokens have no ID token, so
a group taken away at the provider narrows the owner's tokens when the owner
next signs in, not before. Every token therefore expires, after at most
`sql-explorer.auth.token-max-lifetime` (90 days by default). A token cannot
call `/tokens`, so it cannot make, widen or extend tokens.

## The groups claim

`groups` is a JSON array of strings, present in the ID token, the access token
and the userinfo response. It is always present, and is `[]` for a user with no
groups. It is sent whenever `openid` is in scope; no separate `groups` scope is
needed.

Every group follows sql-explorer's scheme, `sqlx:<role>[:<scope>]`
(sqlx-e13.4). The role is `viewer`, `runner`, `author` or `admin`, each
including the one before. The scope is an environment (`local`), one datasource
in it (`local/app`), or nothing for everywhere. A grant covers everything under
its scope but never above it: Runner on `local/app` does not make `local`
readable, which is why `runner` and `author` also carry a Viewer grant.

The environments are the ones `src/main/docker-local/config/application.yml`
defines, `local` and `production`, so each login can be tried against the
local stack. A real provider can keep its own group names and map them in
`sql-explorer.auth.group-grants` instead.

The other claims are `sub` and `preferred_username` (both the login),
`name`, `email` (`<login>@example.com`) and `email_verified`.

## The client

One public client, `sql-explorer`: no secret, authorization code with PKCE
(S256) required, scopes `openid`, `profile` and `email`, and no consent screen.
Redirect URIs are matched exactly:

- `http://localhost:4200/api/sql-explorer/login/oauth2/code/sql-explorer` (through the UI)
- `http://localhost:8080/api/sql-explorer/login/oauth2/code/sql-explorer` (a bare bootRun)
- `http://localhost:18180/api/sql-explorer/login/oauth2/code/sql-explorer` (docker compose, or the `local` profile)

That is Spring Security's default `oauth2Login` callback for a registration
named `sql-explorer`, `{baseUrl}/login/oauth2/code/{registrationId}`, where
`{baseUrl}` includes the backend's servlet context path, `/api/sql-explorer`.
The UI's nginx only proxies that path, so the callback has to live under it.
The client and its URIs are in
[`application.yml`](src/main/resources/application.yml) and can be overridden
from the environment.

## Which issuer

A token names one issuer, and a relying party checks it. The browser and a
backend in the compose network reach this server by different names,
`localhost:18190` and `dev-idp:18190`, so one of them has to be the issuer.

**It is the browser-facing `http://localhost:18190`** (`DEV_IDP_ISSUER`). The
browser is the party that cannot be told about a second address: it follows
the authorize redirect, fills in the login form and follows logout, all at
whatever discovery advertises. The backend can be configured. So a backend in
a container takes the browser-facing values from config and uses the
in-network address only for the back channel:

| Spring client property          | Value                                       |
|---------------------------------|---------------------------------------------|
| `authorization-uri`             | `http://localhost:18190/oauth2/authorize`   |
| `token-uri`                     | `http://dev-idp:18190/oauth2/token`         |
| `jwk-set-uri`                   | `http://dev-idp:18190/oauth2/jwks`          |
| `user-info-uri`                 | `http://dev-idp:18190/userinfo`             |
| expected `iss`                  | `http://localhost:18190`                    |

It must not set `issuer-uri`, which makes Spring fetch discovery at startup,
from `localhost` inside its own container.

The issuer is fixed rather than derived from each request's `Host`. Derived,
the same user would get a different `iss` depending on which name was used,
and a token would validate or not by accident.

Two ways around the split:

- **A backend on the host** (`bootRun`, port 8080) has none: `localhost` works
  for every endpoint, and `issuer-uri: http://localhost:18190` is enough.
- **One name everywhere**: set `DEV_IDP_ISSUER=http://dev-idp:18190` and add
  `127.0.0.1 dev-idp` to the host's `/etc/hosts`. The browser then resolves
  `dev-idp` too, and discovery works from both sides.

The server listens on 18190 inside the container as well as outside, so the two
addresses differ only by host.

## Cookies

The IdP's session cookie is `DEV_IDP_SESSION`, not `JSESSIONID`. Cookies are
scoped by host, not port, so under the default name the IdP on
`localhost:18190` and the backend on `localhost:8080` would overwrite each
other's session on every sign-in.

## Tests

`DevIdpApplicationTest` boots the server and signs in as each user: authorize,
the login form, the code, the token exchange with the PKCE verifier, then
userinfo. It checks the ID token's signature against the JWKS and the `groups`
claim in both the ID token and userinfo.

```bash
./gradlew :dev-idp:test
```
