# syntax=docker/dockerfile:1

# --- build stage: compile the Spring Boot jar with the Maven wrapper ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Warm the Maven cache from the wrapper + POM before copying sources, so code
# changes do not re-download dependencies (go-offline is best-effort).
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q -DskipTests dependency:go-offline || true

COPY src/ src/
RUN ./mvnw -B -q -DskipTests package

# --- runtime stage: slim JRE, non-root user ---
FROM eclipse-temurin:21-jre
WORKDIR /app

# Non-root runtime user; the SQLite database lives under /app/data (a mounted volume).
RUN groupadd --system --gid 1001 app \
 && useradd --system --uid 1001 --gid app app \
 && mkdir -p /app/data \
 && chown -R app:app /app

COPY --from=build /workspace/target/*.jar /app/app.jar
USER app

EXPOSE 8080
ENV CV_DB_PATH=/app/data/cvchecker.db
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
