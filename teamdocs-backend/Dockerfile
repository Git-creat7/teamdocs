# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace

ARG MAVEN_MIRROR_URL=https://repo.maven.apache.org/maven2
ENV MAVEN_MIRROR_URL=${MAVEN_MIRROR_URL}

COPY pom.xml .
COPY settings.xml /tmp/settings.xml
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    set -eu; \
    attempt=1; \
    until mvn -s /tmp/settings.xml -B -ntp \
        -Dmaven.wagon.http.retryHandler.count=5 \
        -Dmaven.wagon.http.retryHandler.requestSentEnabled=true \
        dependency:go-offline; do \
      if [ "$attempt" -ge 3 ]; then exit 1; fi; \
      attempt=$((attempt + 1)); \
      echo "Maven dependency download failed; retrying ($attempt/3)" >&2; \
      sleep 5; \
    done

COPY src ./src
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    set -eu; \
    attempt=1; \
    until mvn -s /tmp/settings.xml -B -ntp \
        -Dmaven.wagon.http.retryHandler.count=5 \
        -Dmaven.wagon.http.retryHandler.requestSentEnabled=true \
        -Dmaven.test.skip=true package; do \
      if [ "$attempt" -ge 3 ]; then exit 1; fi; \
      attempt=$((attempt + 1)); \
      echo "Maven package failed; retrying ($attempt/3)" >&2; \
      sleep 5; \
    done

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

RUN apk add --no-cache curl \
    && addgroup -S teamdocs \
    && adduser -S teamdocs -G teamdocs

COPY --from=build /workspace/target/teamdocs-backend-*.jar app.jar

USER teamdocs
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
