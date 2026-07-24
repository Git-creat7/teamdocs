FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

RUN addgroup -S teamdocs && adduser -S teamdocs -G teamdocs

COPY --from=build /workspace/target/teamdocs-backend-*.jar app.jar

USER teamdocs
EXPOSE 8080

ENTRYPOINT [java, -jar, app.jar]
