# syntax=docker/dockerfile:1
# SPDX-License-Identifier: MIT

# The jar is architecture-independent, so build it once on the native platform and only the
# runtime layer varies per target. Without --platform=$BUILDPLATFORM, buildx would run the
# whole Maven build under QEMU emulation for every extra architecture.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# dependencies first so they stay cached when only sources change
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/mobsf-mcp.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre-alpine
ARG VERSION=dev
ARG SOURCE_URL="https://github.com/sure4docker/mobsf-mcp"
ARG REVISION=unknown
LABEL org.opencontainers.image.title="mobsf-mcp" \
      org.opencontainers.image.description="MCP server for MobSF: upload, scan and report on Android/iOS apps over MCP" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="$VERSION" \
      org.opencontainers.image.revision="$REVISION" \
      org.opencontainers.image.source="$SOURCE_URL" \
      org.opencontainers.image.documentation="$SOURCE_URL#readme"

RUN addgroup -S mcp && adduser -S -G mcp mcp \
 && mkdir -p /data/apks /data/reports && chown -R mcp:mcp /data
WORKDIR /app
COPY --from=build --chown=mcp:mcp /build/extracted/dependencies/ ./
COPY --from=build --chown=mcp:mcp /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=mcp:mcp /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=mcp:mcp /build/extracted/application/ ./
USER mcp

ENV MCP_TRANSPORT=http \
    PORT=8080 \
    UPLOAD_DIR=/data/apks \
    REPORT_DIR=/data/reports \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:TieredStopAtLevel=1"
EXPOSE 8080
VOLUME ["/data/apks", "/data/reports"]

# Only meaningful in http mode; stdio containers report as unhealthy-but-running, which is expected.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/actuator/health" > /dev/null 2>&1 || exit 1

# No CMD: arguments after the image name (e.g. --transport=stdio) reach the application.
ENTRYPOINT ["java", "-jar", "mobsf-mcp.jar"]
