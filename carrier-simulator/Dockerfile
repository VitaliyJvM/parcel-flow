# --- Build stage -------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
# Both module build scripts: settings.gradle includes tracking-service, so Gradle
# needs its script present to configure the build even when only building this module.
COPY tracking-service/build.gradle tracking-service/build.gradle
COPY carrier-simulator/build.gradle carrier-simulator/build.gradle
RUN ./gradlew --no-daemon :carrier-simulator:downloadDependencies

COPY carrier-simulator/src carrier-simulator/src
RUN ./gradlew --no-daemon :carrier-simulator:bootJar -x test

# --- Runtime stage -----------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime

RUN groupadd --system parcelflow && useradd --system --gid parcelflow parcelflow

WORKDIR /app
COPY --from=build /workspace/carrier-simulator/build/libs/carrier-simulator.jar app.jar
RUN chown parcelflow:parcelflow /app/app.jar

USER parcelflow

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# Arguments are appended by `docker compose run carrier-simulator --shipment-id=... `.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
