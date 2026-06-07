# syntax=docker/dockerfile:1.7

FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY docker/maven-settings.xml /tmp/maven-settings.xml
COPY backend/pom.xml backend/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn --settings /tmp/maven-settings.xml -f backend/pom.xml -DskipTests dependency:go-offline

COPY backend backend
RUN --mount=type=cache,target=/root/.m2 \
    mvn --settings /tmp/maven-settings.xml -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system mamoji \
    && useradd --system --gid mamoji --home-dir /app --shell /usr/sbin/nologin mamoji

COPY --from=build /workspace/backend/target/*.jar /app/app.jar
RUN chown -R mamoji:mamoji /app

USER mamoji

EXPOSE 38080
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=12 \
  CMD curl -fsS http://localhost:38080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
