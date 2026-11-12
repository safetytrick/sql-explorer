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

## Logins

The password is the username.

| Login      | Groups                                           | What it is for                         |
|------------|--------------------------------------------------|----------------------------------------|
| `viewer`   | `sqlx:viewer`                                    | Viewer                                 |
| `runner`   | `sqlx:runner:app-staging`                        | Runner on one datasource               |
| `author`   | `sqlx:author:app-staging`                        | Author on the same datasource          |
| `mixed`    | `sqlx:runner:app-staging`, `sqlx:author:app-prod` | Different roles on different datasources |
| `admin`    | `sqlx:admin`                                     | Admin                                  |
| `nogroups` | (none, an empty list)                            | Signed in but granted nothing          |

They are defined in
[`DevUsers.java`](src/main/java/com/github/safetytrick/sqlexplorer/devidp/DevUsers.java).

## The groups claim

`groups` is a JSON array of strings, present in the ID token, the access token
and the userinfo response. It is always present, and is `[]` for a user with no
groups. It is sent whenever `openid` is in scope; no separate `groups` scope is
needed.

Every group follows one scheme, which sqlx-e13.4 maps to roles:

| Group                    | Meaning                                    |
|--------------------------|--------------------------------------------|
| `sqlx:viewer`            | Viewer, on every datasource                |
| `sqlx:admin`             | Admin, on every datasource                 |
| `sqlx:runner:<datasource>` | Runner (run saved queries) on one datasource |
| `sqlx:author:<datasource>` | Author (write and edit SQL) on one datasource |

The `sqlx:` prefix keeps these apart from whatever else a real IdP puts in
`groups`. A datasource name may contain letters, digits, `-` and `_`, never
`:`. The datasources here are `app-staging` and `app-prod`.

Each user carries exactly the groups listed and nothing implied: `runner` is
not also given `sqlx:viewer`. Whether one role implies another belongs to the
mapping, so these users test it rather than paper over it.

The other claims are `sub` and `preferred_username` (both the login),
`name`, `email` (`<login>@example.com`) and `email_verified`.

## The client

One public client, `sql-explorer`: no secret, authorization code with PKCE
(S256) required, scopes `openid`, `profile` and `email`, and no consent screen.
Redirect URIs are matched exactly:

- `http://localhost:4200/login/oauth2/code/sql-explorer` (ng serve)
- `http://localhost:8080/login/oauth2/code/sql-explorer` (bootRun)
- `http://localhost:18180/login/oauth2/code/sql-explorer` (docker compose)

That is Spring Security's default `oauth2Login` callback for a registration
named `sql-explorer`. The client and its URIs are in
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
