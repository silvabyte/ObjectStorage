# ObjectStorage

A standalone object storage service built with Scala 3 and Cask. Provides multi-tenant file management with checksums, deduplication, and distributed locking.

## Features

- **Multi-tenant file storage** - Isolated storage per tenant/user
- **Checksum-based deduplication** - SHA-256 checksums for integrity and dedup
- **File locking** - File-based locks for concurrent append operations
- **REST API** - Simple HTTP API for upload, download, list, delete
- **Streaming uploads** - Efficient handling of large files
- **Metadata management** - Custom metadata per file

## Quick Start

### Prerequisites

- JDK 21+
- [Mill](https://mill-build.org/) build tool

### Build & Run

```bash
# Compile
./mill ObjectStorage.compile

# Run tests
./mill ObjectStorage.test

# Run the server
./mill ObjectStorage.run

# Build assembly JAR
./mill ObjectStorage.assembly
```

### Docker

```bash
# Build image
docker build -t objectstorage -f docker/Dockerfile .

# Run with docker-compose
docker-compose -f docker/compose.yml up
```

## API

See [resources/openapi.yaml](resources/openapi.yaml) for the full OpenAPI specification.

### Endpoints

| Method | Path                                | Description               |
| ------ | ----------------------------------- | ------------------------- |
| GET    | `/api/v1/files/list`                | List all files for tenant |
| POST   | `/api/v1/files`                     | Upload file (stream)      |
| POST   | `/api/v1/files/form`                | Upload file (multipart)   |
| GET    | `/api/v1/files/{objectId}`          | Download file             |
| GET    | `/api/v1/files/metadata/{objectId}` | Get file metadata         |
| DELETE | `/api/v1/files/{objectId}`          | Delete file               |
| GET    | `/api/v1/files/checksum/{checksum}` | Find file by checksum     |

### Headers

All requests require:

- `x-tenant-id` - Tenant identifier
- `x-user-id` - User identifier

## Configuration

Environment variables:

| Variable              | Description       | Default   |
| --------------------- | ----------------- | --------- |
| `OBJECT_STORAGE_HOST` | Server host       | `0.0.0.0` |
| `OBJECT_STORAGE_PORT` | Server port       | `8080`    |
| `LOG_PRETTY`          | Pretty print logs | `false`   |

## Development

```bash
# Format code
./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources

# Lint check
./mill fixCheckAll

# Auto-fix lint issues
./mill fixAll
```

## License

MIT License - see [LICENSE](LICENSE)
