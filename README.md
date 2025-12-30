# ObjectStorage

A standalone object storage service built with Scala 3 and Cask. Provides multi-tenant file management with checksums, deduplication, and append only locking.

## Features

- **Multi-tenant file storage** - Isolated storage per tenant/user
- **Checksum-based deduplication** - SHA-256 checksums for integrity and dedup
- **REST API** - Simple HTTP API for upload, download, list, delete
- **Streaming uploads** - Efficient handling of large files
- **File locking** - File-based locks for concurrent append only operations
- **Metadata management** - Custom metadata per file

AI Disclaimer: This project uses AI assistance for documentation creation as well as code generation for monotonous tasks. All architecture, design and more interesting code creation is done by a [human](https://x.com/MatSilva)

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

## Documentation

Full documentation is available in the [docs/](docs/) folder:

- [API Reference](docs/api.md) - Complete REST API documentation
- [Authentication](docs/authentication.md) - API key and identity headers
- [Configuration](docs/configuration.md) - Environment variables and settings
- [Storage](docs/storage.md) - File organization, deduplication, and locking
- [Client SDK](docs/client.md) - Scala HTTP client usage

### Quick API Overview

| Method | Path                                | Description               |
| ------ | ----------------------------------- | ------------------------- |
| GET    | `/api/v1/files/list`                | List all files for tenant |
| POST   | `/api/v1/files`                     | Upload file (stream)      |
| POST   | `/api/v1/files/form`                | Upload file (multipart)   |
| GET    | `/api/v1/files/{objectId}`          | Download file             |
| GET    | `/api/v1/files/metadata/{objectId}` | Get file metadata         |
| DELETE | `/api/v1/files/{objectId}`          | Delete file               |
| GET    | `/api/v1/files/checksum/{checksum}` | Find file by checksum     |

All file endpoints require headers: `x-api-key`, `x-tenant-id`, `x-user-id`

### Quick Configuration

| Variable              | Description       | Default        |
| --------------------- | ----------------- | -------------- |
| `OBJECT_STORAGE_HOST` | Server host       | `0.0.0.0`      |
| `OBJECT_STORAGE_PORT` | Server port       | `8080`         |
| `STORAGE_BASE_PATH`   | Storage directory | `./bucket`     |
| `API_KEY`             | API key for auth  | `test-api-key` |
| `LOG_PRETTY`          | Pretty print logs | `false`        |

See [Configuration](docs/configuration.md) for all options.

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
