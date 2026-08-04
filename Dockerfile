# syntax=docker/dockerfile:1

# ── build stage ───────────────────────────────────────────────────────────
# Complete in-image build (no gradle on the host): the wrapper downloads the
# gradle distribution and dependencies into a BuildKit cache mount, so
# rebuilds only pay for compilation. Mirrors ui/Dockerfile's two-phase shape.
FROM amazoncorretto:21 AS builder

# xargs is needed for gradle
RUN yum install -y findutils && yum clean all

WORKDIR /workspace

# Build scripts first: the dependency-resolution layer stays cached until
# the build configuration itself changes.
COPY gradlew gradle.properties settings.gradle build.gradle ./
COPY gradle gradle
COPY models/build.gradle models/build.gradle

# Warm the wrapper + dependency caches (best-effort; assemble below is the gate).
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew --no-daemon --quiet dependencies > /dev/null 2>&1 || true

# Sources last: models/ first (Avro codegen input), then the application.
COPY .git .git
COPY models/src models/src
COPY src src

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon assemble

# ── runtime stage ─────────────────────────────────────────────────────────
FROM amazoncorretto:21

ENV ARTIFACT=sql-explorer.jar
ENV APP_PROP_CONFIG_DIR=/etc/sql-explorer/config/,optional:/etc/sql-explorer/secrets/

WORKDIR /app
COPY --from=builder /workspace/build/libs/sql-explorer.jar $ARTIFACT

VOLUME /etc/sql-explorer/config

ENTRYPOINT exec java $JAVA_OPTS \
    -Dspring.config.additional-location=$APP_PROP_CONFIG_DIR \
    -Dspring.profiles.include=$ENVIRONMENT_PROFILE \
    -jar $ARTIFACT
