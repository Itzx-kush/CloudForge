# ==============================================================================
# Stage 1: Build Spring Boot executable JAR with Maven & JDK 17
# ==============================================================================
FROM maven:3.9.9-eclipse-temurin-17-alpine AS build

WORKDIR /workspace

# Cache Maven dependencies before copying application source
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

# Copy source and build fat JAR (unit tests run separately in CI and local dev)
COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

# ==============================================================================
# Stage 2: Minimal Java 17 Runtime Image (non-root user)
# ==============================================================================
FROM eclipse-temurin:17-jre-alpine AS runtime

WORKDIR /app

RUN addgroup -S cloudforge && adduser -S cloudforge -G cloudforge

COPY --from=build /workspace/target/cloudforge-backend.jar /app/app.jar
RUN chown -R cloudforge:cloudforge /app

USER cloudforge

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=25s --retries=5 \
    CMD wget -q --spider http://127.0.0.1:8080/api/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
