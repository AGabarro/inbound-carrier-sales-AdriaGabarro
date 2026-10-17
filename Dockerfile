# Stage 1: build with the JDK + Maven wrapper (no Java/Maven needed on the host)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
# Cached layer: dependencies are only re-downloaded when pom.xml changes
RUN ./mvnw -B -q dependency:go-offline
COPY src src
# Runs the tests too: a broken build never produces an image
RUN ./mvnw -B -q package

# Stage 2: slim runtime, JRE only, non-root
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
  CMD curl -fsS http://localhost:${PORT:-8080}/health || exit 1
# Default JVM heap is 25% of container memory; this is the only process, so give it more
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
