# Images for the one-click launcher (Start AI Workforce OS.command / .bat).
#
# Unlike infra/Dockerfile, the whole reactor is compiled once in a stage that does not depend on
# SERVICE. BuildKit therefore builds it a single time and shares it across all eight images,
# which makes the first launch several times faster than eight separate Maven builds.

# Pinned to the Maven release .mvn/wrapper/maven-wrapper.properties uses. The tag was chosen and
# confirmed with docker manifest inspect, not read from a local image: none is kept after a build.
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY platform-contracts platform-contracts
COPY platform-core platform-core
COPY platform-web platform-web
COPY llm-core llm-core
COPY mcp-core mcp-core
COPY services services
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q install -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true

# The runtime is pinned to the exact JRE the images were built and tested with.
FROM eclipse-temurin:21.0.12.1_1-jre-alpine AS runtime
RUN apk add --no-cache curl tzdata && addgroup -S aiwos && adduser -S -G aiwos aiwos
WORKDIR /app
ARG SERVICE
COPY --from=build --chown=aiwos:aiwos /workspace/services/${SERVICE}/target/${SERVICE}.jar app.jar
USER aiwos
ENV SERVER_PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
