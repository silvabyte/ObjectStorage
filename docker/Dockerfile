# ObjectStorage - Standalone Object Storage Service
# Multi-stage build for smaller final image

# Build stage (optional - use if building from source)
FROM eclipse-temurin:21-jdk-alpine AS builder

# Install curl for mill wrapper script
RUN apk add --no-cache curl

WORKDIR /build

# Copy mill wrapper and build files
COPY mill mill
COPY build.mill build.mill
COPY .mill-version .mill-version
COPY ObjectStorage/ ObjectStorage/

# Build the assembly JAR
RUN chmod +x mill && ./mill ObjectStorage.assembly

# Final runtime image
FROM eclipse-temurin:21-jre-alpine

# Install curl for healthchecks
RUN apk add --no-cache curl

WORKDIR /app

# Copy built JAR from builder stage
COPY --from=builder /build/out/ObjectStorage/assembly.dest/out.jar /app/objectstorage.jar

# Create non-root user for security
RUN addgroup -g 1001 -S objectstorage && \
    adduser -u 1001 -S objectstorage -G objectstorage && \
    mkdir -p /app/bucket && \
    chown -R objectstorage:objectstorage /app

USER objectstorage

# Expose default port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:${OBJECT_STORAGE_PORT:-8080}/api/v1/health || exit 1

# JVM options for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Use Java to run the JAR
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/objectstorage.jar"]
