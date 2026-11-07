# syntax=docker/dockerfile:1

# Image of the web toolkit (JFR viewer, collapsed stack viewer and GC logs analyzer), built from the sources:
#
#   docker build -t gclogs/toolkit-web-application:1.0-SNAPSHOT .
#   docker run -it -p 8079:8079 -v /var/uploads:/uploads gclogs/toolkit-web-application:1.0-SNAPSHOT
#
# The gclogs.com site is run with -e SPRING_PROFILES_ACTIVE=gclogs-com-site. The configuration options are
# described in README.md.

# ---- Build of the application jar ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY . .
# The local Maven repository is cached between the builds. The downloads are logged, the first build fetches
# every dependency and its progress should be visible.
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --projects toolkit-web-application --also-make package -DskipTests

# ---- Jar split into the Spring Boot layers ----
FROM eclipse-temurin:17-jre AS layers
WORKDIR /layers
COPY --from=build /build/toolkit-web-application/target/toolkit-web-application.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- Runtime ----
FROM eclipse-temurin:17-jre
RUN useradd --system --uid 1001 --create-home toolkit \
    && mkdir /uploads /logs \
    && chown toolkit /uploads /logs
WORKDIR /application
# Every layer is a separate image layer, from the least to the most often changed one - a new version of the
# application changes only the last ones
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./
USER toolkit

# Logs uploaded to the GC logs analyzer and the access log of the gclogs.com site (gclogs-com-site profile); the
# parsed recordings are kept in memory, so the heap gets most of the memory available to the container
ENV UPLOAD_DIR=/uploads \
    ACCESS_LOG_DIR=/logs \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
VOLUME /uploads
EXPOSE 8079

ENTRYPOINT ["java", "-jar", "application.jar"]
