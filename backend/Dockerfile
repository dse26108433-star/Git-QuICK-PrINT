# ---------------------------------------------------------------
# The Campus Print backend as a container, for any cloud host
# (Render, Railway, Fly.io, a VPS with Docker...).
# Settings come from environment variables: the same names as in
# .env.example (DB_URL, SUPABASE_URL, COUNTER_PASSWORD, ...).
# ---------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 campusprint
COPY --from=build /src/target/print-server-1.0.0.jar app.jar
USER campusprint
# Use most of the container's memory; the host sets PORT (default 8080).
# Health check for the host: GET /actuator/health
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
