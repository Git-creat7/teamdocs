FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace

ARG MAVEN_MIRROR_URL=https://repo.maven.apache.org/maven2
ENV MAVEN_MIRROR_URL=${MAVEN_MIRROR_URL}

COPY pom.xml .
COPY settings.xml /tmp/settings.xml
RUN mvn -s /tmp/settings.xml -B -ntp \
    -Dmaven.wagon.http.retryHandler.count=5 \
    dependency:go-offline

COPY src ./src
RUN mvn -s /tmp/settings.xml -B -ntp \
    -Dmaven.wagon.http.retryHandler.count=5 \
    -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

RUN apk add --no-cache curl \
    && addgroup -S teamdocs \
    && adduser -S teamdocs -G teamdocs

COPY --from=build /workspace/target/teamdocs-backend-*.jar app.jar

USER teamdocs
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
