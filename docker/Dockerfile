# Final runtime image
FROM openjdk:21-jdk-slim

# Install curl for healthchecks
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy built JAR from mill assembly output
COPY out/ObjectStorage/assembly.dest/out.jar /app/objectstorage.jar

# Copy environment template
COPY docker/docker.env /app/.env.example

# Expose default port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/ || exit 1

# Use Java to run the JAR
ENTRYPOINT ["java", "-jar", "/app/objectstorage.jar"]
