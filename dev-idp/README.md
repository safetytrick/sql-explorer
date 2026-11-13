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

It publishes no port. The UI's nginx proxies `/idp/` to it, so in Docker
everything goes through <http://localhost:4200>: discovery is at
<http://localhost:4200/idp/.well-known/openid-configuration>, and the login
form and logout live there too (see "Behind the UI's nginx").

Or without Docker, directly on port 18190:

```bash
./gradlew :dev-idp:bootRun
```

Discovery is then at <http://localhost:18190/idp/.well-known/openid-configuration>.

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

Then open <http://localhost:4200>, which sends you to the IdP's login form
at `localhost:4200/idp/login`;
sign in as, say, `viewer` / `viewer`. The nav shows who you are and has
"Sign out", which also ends the IdP session (RP-initiated logout).
`/api/sql-explorer/user` shows the `groups` claim as received.

On the host, start the IdP with `./gradlew :dev-idp:bootRun` and run the
backend with `-Dspring.config.additional-location=src/main/docker-local/config/
-Dspring.profiles.include=local,dev-idp` (port 18180). Every endpoint is
`localhost:18190/idp` then, which is the default on both sides. There is no
UI on 18180, so sign in at
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

## Behind the UI's nginx

The IdP serves under the context path `/idp`, and `ui/nginx.conf` proxies
`/idp/` to `dev-idp:18190` the way it proxies `/api/sql-explorer/` to the
backend. The browser therefore only ever talks to `localhost:4200`: the app,
the authorize redirect, the login form and logout are all one origin.

nginx sends `X-Forwarded-Host`, `-Proto` and `-Port: 4200`, and the IdP runs
with `server.forward-headers-strategy: native`, so the redirects it builds
(to the login form, back to authorize) name `localhost:4200`, not the
container's port. `DevIdpApplicationTest` pins that.

The location resolves `dev-idp` at request time, through Docker's resolver,
so nginx still starts in a stack without the IdP; `/idp/` is a 502 there.

## Which issuer

A token names one issuer, and a relying party checks it. In Docker it is the
browser-facing **`http://localhost:4200/idp`** (`DEV_IDP_ISSUER`), fixed
rather than derived from each request's `Host`. Derived, the same user would
get a different `iss` depending on which name was used, and a token would
validate or not by accident.

A backend in a container still cannot reach `localhost:4200`, which is its own
container, so it takes the browser-facing values from config and uses the
in-network address only for the back channel:

| Spring client property          | Value                                         |
|---------------------------------|-----------------------------------------------|
| `authorization-uri`             | `http://localhost:4200/idp/oauth2/authorize`  |
| `token-uri`                     | `http://dev-idp:18190/idp/oauth2/token`       |
| `jwk-set-uri`                   | `http://dev-idp:18190/idp/oauth2/jwks`        |
| `user-info-uri`                 | `http://dev-idp:18190/idp/userinfo`           |
| expected `iss`                  | `http://localhost:4200/idp`                   |

It must not set `issuer-uri`, which makes Spring fetch discovery at startup,
from `localhost` inside its own container.

A backend on the host has no split. Against the IdP in Docker it can reach
everything at `localhost:4200/idp`; against a bootRun IdP, at
`localhost:18190/idp`, which is the default issuer on both sides.

## Cookies

The IdP's session cookie is `DEV_IDP_SESSION`, with path `/idp`, not
`JSESSIONID`. Behind nginx the IdP and the backend share an origin, and even
directly cookies are scoped by host, not port: under the default name the IdP
and the backend would overwrite each other's session on every sign-in.

## Tests

`DevIdpApplicationTest` boots the server and signs in as each user: authorize,
the login form, the code, the token exchange with the PKCE verifier, then
userinfo. It checks the ID token's signature against the JWKS and the `groups`
claim in both the ID token and userinfo.

```bash
./gradlew :dev-idp:test
```
