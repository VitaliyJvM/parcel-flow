# --- Build stage -------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Build scripts first. They change far less often than source, so the Gradle
# distribution download and the dependency cache below survive code edits.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY tracking-service/build.gradle tracking-service/build.gradle
RUN ./gradlew --no-daemon :tracking-service:downloadDependencies

COPY tracking-service/src tracking-service/src
# -x test: the image is built from an already-tested commit. Tests need a Docker
# socket for Testcontainers, which is not available inside a build container.
RUN ./gradlew --no-daemon :tracking-service:bootJar -x test

# --- Runtime stage -----------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime

# curl is only here to give Compose a real HTTP health check against the
# readiness probe. A port check would report healthy before Flyway has finished.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system parcelflow && useradd --system --gid parcelflow parcelflow

WORKDIR /app
COPY --from=build /workspace/tracking-service/build/libs/tracking-service-*.jar app.jar
RUN chown parcelflow:parcelflow /app/app.jar

USER parcelflow
EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the heap then tracks whatever memory
# limit the container is given, without rebuilding the image.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
