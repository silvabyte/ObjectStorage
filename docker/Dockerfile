# Build stage
FROM golang:1.24-alpine AS builder
WORKDIR /build
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o /objectstorage ./cmd/objectstorage

# Runtime stage
FROM alpine:3.21
LABEL service="objectstorage"
RUN adduser -D -u 1001 objectstorage
WORKDIR /app
COPY --from=builder /objectstorage /app/objectstorage
RUN mkdir -p /app/bucket && chown -R objectstorage:objectstorage /app
USER objectstorage
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://localhost:8080/api/v1/health || exit 1
ENTRYPOINT ["/app/objectstorage"]
