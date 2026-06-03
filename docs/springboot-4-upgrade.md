# Spring Boot 3 → 4 Upgrade Plan

Upgrade the backend from Spring Boot 3.4.2 to Spring Boot 4.0, using OpenRewrite to automate as much of the migration as possible.

## Current State

- Spring Boot **3.4.2** (applied via legacy `buildscript` block in `build.gradle`)
- Java 21 toolchain (already exceeds Boot 4's Java 17 baseline — no change needed)
- Gradle wrapper **8.12.1** — below Boot 4's minimum of **8.14**
- Multi-project build: root + `models/` (Avro)
- springdoc-openapi 2.8.4, Liquibase, JPA/Hibernate, MySQL connector

## What the OpenRewrite Recipe Handles

Recipe: `org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0`
(from `org.openrewrite.recipe:rewrite-spring:6.32.0`, Community Edition)

The recipe chains through 3.5 first, so starting from 3.4.2 is fine. It covers:

- Spring Boot 3.4 → 3.5 → 4.0.x, Spring Framework 7.0, Spring Security 7.0
- Hibernate 7.1.x, SpringDoc 3.0
- **Modular starters**: renames `spring-boot-starter-web` → `spring-boot-starter-webmvc`, etc.
- `@MockBean`/`@SpyBean` → `@MockitoBean`/`@MockitoSpyBean`
- Property migrations to 4.0 format (`application.yml`)
- Deprecated/preferred API replacements

## Known Project-Specific Issues (manual work)

These are things the recipe won't fully handle, found by inspecting the build:

1. **Gradle wrapper 8.12.1 → 8.14+** (or 9.x). Must happen *before* the Boot 4
   plugin will apply. `./gradlew wrapper --gradle-version 8.14.3` (or latest 9.x).

2. **Legacy `buildscript` plugin application.** `spring-boot-gradle-plugin` is
   applied via `buildscript { }` + `apply plugin:`. OpenRewrite handles the
   `plugins { }` DSL better — consider modernizing to
   `id 'org.springframework.boot' version '...'` first (or verify the recipe
   updates the buildscript version correctly).

3. **Jersey is dropped in Spring Boot 4.** We depend on
   `org.glassfish.jersey.core:jersey-server` but don't actually use Jersey as a
   server — only two files import `jakarta.ws.rs`:
   - `evidence/source/SqlSourceResource.java` — `jakarta.ws.rs.core.HttpHeaders` constant
   - `profiles/SqlProfileReportResource.java` — `@QueryParam`/`@DefaultValue` annotations
     (these are JAX-RS annotations on a Spring MVC controller; replace with
     Spring's `@RequestParam(defaultValue = ...)`)

   Action: replace those usages, drop `jersey-server` and `jakarta.ws.rs-api`.

4. **Pinned dependency versions that fight the BOM:**
   - `spring-boot-starter-validation:3.2.3` — hardcoded old version; drop the pin
     and let the BOM manage it
   - `jakarta.persistence-api:3.1.0` — Hibernate 7 uses Jakarta Persistence 3.2;
     drop the pin or let the BOM manage it
   - `jakarta.inject`, `jakarta.transaction`, `jakarta.validation` pins — prefer
     BOM-managed versions where available
   - `javax.inject:javax.inject:1` — legacy javax artifact; check what needs it
     and remove if possible

5. **Lombok 1.18.30** — bump annotation processor to latest (BOM-managed
   `org.projectlombok:lombok` without explicit version is safer).

6. **Manifold compiler plugin** (`-Xplugin:Manifold`) — not Spring-related, but
   verify it still compiles cleanly after the Gradle/dependency changes.

7. **springdoc 2.8.4 → 3.x** — required for Boot 4 / Framework 7. The recipe
   claims to handle this; verify the Swagger UI still works after.

8. **Hibernate 6 → 7** — JPA behavior changes (e.g., stricter type handling,
   changed defaults). Our JPA layer (profiles, commentary, analysis persistence)
   needs a real regression pass against MySQL, not just unit tests.

9. **Avro / Parquet / Hadoop deps** — not managed by Spring; should be
   unaffected, but `models/` subproject participates in `allprojects` config, so
   confirm it still builds.

## Execution Plan

### Phase 1 — Pre-flight (separate commit(s))
1. Upgrade Gradle wrapper to 8.14+ and confirm `./gradlew assemble test` passes on Boot 3.4.2.
2. Modernize plugin application to the `plugins { }` DSL.
3. Remove the JAX-RS usage in the two `*Resource.java` files; drop `jersey-server` + `jakarta.ws.rs-api`.
4. Drop stale version pins (`spring-boot-starter-validation:3.2.3`, Lombok version, jakarta pins where BOM-managed).
5. Green build + tests = baseline.

### Phase 2 — OpenRewrite run
Add temporarily to `build.gradle`:

```gradle
plugins {
    id 'org.openrewrite.rewrite' version 'latest.release'
}

rewrite {
    activeRecipe('org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0')
    setExportDatatables(true)
}

dependencies {
    rewrite('org.openrewrite.recipe:rewrite-spring:6.32.0')
}
```

1. `./gradlew rewriteDryRun` — review `build/reports/rewrite/rewrite.patch` first.
2. `./gradlew rewriteRun` — apply.
3. Review the diff carefully (build files, yml properties, test annotations).
4. Remove the rewrite plugin/config once done (or keep behind a flag for future upgrades).

### Phase 3 — Manual fixes & verification
1. Fix anything the recipe missed (compile errors, springdoc config, Hibernate 7 issues).
2. `./gradlew assemble test` green.
3. Full local stack check: `docker compose up` (mysql → backend → ui), exercise:
   - table/schema browsing (`/tables/:schema`)
   - query profiles (`/analyzed-sql/:id`)
   - table graph
   - tagging/notes (writes through JPA — exercises Hibernate 7)
   - actuator + swagger UI endpoints
4. SpotBugs / dependency-check still pass (suppression file may need updates).

### Phase 4 — Cleanup
- Remove any leftover compatibility shims.
- Update `CLAUDE.md` (Boot version reference) and this doc's status.

## Status

- [x] Phase 1 — Pre-flight (Gradle 8.14.3, plugins DSL, Jersey/JAX-RS removed, pins dropped)
- [x] Phase 2 — OpenRewrite run (`rewriteRun` applied; needed `org.gradle.jvmargs=-Xmx4g`)
- [x] Phase 3 — Manual fixes & verification
- [x] Phase 4 — Cleanup (rewrite plugin removed, CLAUDE.md updated)

**Completed 2026-06-03** on branch `upgrade/spring-boot-4`. Now on Spring Boot 4.0.6,
Spring Framework 7, Hibernate 7, Jackson 3 (`tools.jackson`), springdoc 3,
mysql-connector-j 9.x, Gradle wrapper 8.14.5.

### Manual fixes that were needed beyond the recipe

- **Jackson 3 API renames**: `MappingIterator.getCurrentLocation()` → `currentLocation()`;
  `JacksonException` is unchecked; `StdSerializer.serialize` can't throw `IOException`
- **Hibernate 7**: `new StandardLobCreator(session)` → `session.getLobCreator()`
- **Framework 7**: `PathMatchConfigurer.setUseSuffixPatternMatch` removed (override deleted);
  `Jackson2ObjectMapperBuilder`/`MappingJackson2HttpMessageConverter` replaced with
  `JacksonJsonHttpMessageConverter(JsonMapper)`; `ObjectMapperConfig` bean retyped to `JsonMapper`
- **Connector/J 9**: `getIndexInfo(null, null, null, ...)` now throws "Table not specified";
  `IndexLoadingService` queries `INFORMATION_SCHEMA.STATISTICS` directly instead
  (same pattern as `ForeignKeyLoadingService`)

### Verified

- `./gradlew assemble test` green (66 tests)
- Docker stack: health UP; smoke-tested schemas/tables/sources/graph/reports/tags
  endpoints, tag create+read+delete (Hibernate 7 write path), swagger UI, v3/api-docs,
  UI nginx proxy → backend

### Pre-existing issues found during verification (both fixed as follow-ups on this branch)

- `POST /api/sql-explorer/sources` multipart upload 500'd: `calculateMd5Hash(resource.getURL())`
  in `SqlSourceEntityService` — `MultipartFile` resources are never URL-resolvable. Fixed to
  hash from `getInputStream()`. Verified: upload returns 201, source persisted with md5
  (also confirms the Hibernate 7 `getLobCreator()` blob path end-to-end).
- `/actuator/prometheus` was 404: `micrometer-registry-prometheus` was on the classpath but
  no `management.endpoints.web.exposure.include` was configured. Exposing it also required
  a `ByteArrayHttpMessageConverter` in `WebConfig`, whose `configureMessageConverters`
  replaces the default converter list (the endpoint writes `byte[]`).

## References

- [OpenRewrite: Migrate to Spring Boot 4.0 (Community Edition)](https://docs.openrewrite.org/recipes/java/spring/boot4/upgradespringboot_4_0-community-edition)
- [Spring Boot 4 upgrade with OpenRewrite (walkthrough)](https://codeka.io/en/2025/12/12/spring-boot-4-upgrade-with-openrewrite/)
