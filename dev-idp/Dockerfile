# syntax=docker/dockerfile:1

# The dev OIDC provider (sqlx-e13.3). Built from the repository root, because
# Gradle has to see the whole multi-project build to build one project of it:
#
#   docker compose -f docker-compose.yml -f docker-compose.dev-idp.yml up -d dev-idp
#
# Same two-stage shape as the backend's Dockerfile: only the builder pulls
# anything, and the runtime stage places one jar.

# ── build stage ───────────────────────────────────────────────────────────
FROM amazoncorretto:21 AS builder

# xargs is needed for gradle
RUN yum install -y findutils && yum clean all

WORKDIR /workspace

# Every project's build script, since settings.gradle includes them all; only
# dev-idp's sources are needed.
COPY gradlew gradle.properties settings.gradle build.gradle ./
COPY gradle gradle
COPY models/build.gradle models/build.gradle
COPY dev-idp/build.gradle dev-idp/build.gradle
COPY dev-idp/src dev-idp/src

RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew --no-daemon :dev-idp:bootJar

# ── runtime stage ─────────────────────────────────────────────────────────
FROM amazoncorretto:21

RUN yum install -y shadow-utils && yum clean all && \
    useradd --system --uid 1001 --user-group --home-dir /app --shell /sbin/nologin devidp && \
    mkdir -p /app/data && chown devidp:devidp /app/data

WORKDIR /app
COPY --from=builder --chown=devidp:devidp /workspace/dev-idp/build/libs/dev-idp.jar dev-idp.jar

USER devidp
EXPOSE 18190

ENTRYPOINT ["java", "-jar", "/app/dev-idp.jar"]
