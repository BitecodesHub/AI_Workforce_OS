# One Dockerfile for all eight services.
#
# The service is chosen at build time with --build-arg SERVICE=identity-service, so a change to
# how services are packaged is made once rather than in eight nearly identical files that drift.

# ---- Build -----------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Dependencies are resolved from the POMs alone first. A change to source code then reuses this
# layer, which turns a rebuild from several minutes into several seconds.
COPY pom.xml ./
COPY platform-contracts/pom.xml platform-contracts/
COPY platform-core/pom.xml platform-core/
COPY platform-web/pom.xml platform-web/
COPY llm-core/pom.xml llm-core/
COPY mcp-core/pom.xml mcp-core/
COPY services/gateway/pom.xml services/gateway/
COPY services/identity-service/pom.xml services/identity-service/
COPY services/org-service/pom.xml services/org-service/
COPY services/orchestrator-service/pom.xml services/orchestrator-service/
COPY services/memory-service/pom.xml services/memory-service/
COPY services/knowledge-service/pom.xml services/knowledge-service/
COPY services/integrations-service/pom.xml services/integrations-service/
COPY services/analytics-service/pom.xml services/analytics-service/
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q dependency:go-offline -DskipTests || true

COPY platform-contracts/src platform-contracts/src
COPY platform-core/src platform-core/src
COPY platform-web/src platform-web/src
COPY llm-core/src llm-core/src
COPY mcp-core/src mcp-core/src
COPY services services

ARG SERVICE
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q -pl services/${SERVICE} -am install \
        -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true

# The layered jar is expanded here so the runtime image can copy each layer separately.
RUN mkdir -p /extracted && cd /extracted && \
    java -Djarmode=layertools -jar /workspace/services/${SERVICE}/target/${SERVICE}.jar extract

# ---- Run -------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# A non-root user, and a read-only filesystem apart from /tmp. A container that cannot write to
# its own image cannot be made to persist anything an attacker drops into it.
RUN addgroup -S aiwos && adduser -S -G aiwos aiwos && \
    apk add --no-cache curl tzdata && \
    mkdir -p /app /tmp/aiwos && chown -R aiwos:aiwos /app /tmp/aiwos

WORKDIR /app
USER aiwos

# Copied in order of how often each changes, so a code change re-uploads only the last layer.
COPY --from=build --chown=aiwos:aiwos /extracted/dependencies/ ./
COPY --from=build --chown=aiwos:aiwos /extracted/spring-boot-loader/ ./
COPY --from=build --chown=aiwos:aiwos /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=aiwos:aiwos /extracted/application/ ./

ARG SERVICE
ENV SERVICE_NAME=${SERVICE} \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ExitOnOutOfMemoryError \
-Djava.security.egd=file:/dev/urandom -Duser.timezone=UTC" \
    SERVER_PORT=8080

EXPOSE 8080

# Container-aware settings above: MaxRAMPercentage respects the cgroup limit, where a fixed -Xmx
# would either waste the allowance or be killed by the kernel. ExitOnOutOfMemoryError makes the
# orchestrator restart a process that can no longer serve, rather than leaving it thrashing.

HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=4 \
    CMD curl -fsS http://localhost:${SERVER_PORT}/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
