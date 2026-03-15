# ObjectStorage

A multi-tenant file storage microservice built with Go, [huma](https://huma.rocks/), and chi. Provides file management with checksums, deduplication, and append-only locking.

## Features

- **Multi-tenant file storage** — Isolated storage per tenant/user
- **Checksum-based deduplication** — SHA-256 checksums for integrity and dedup
- **REST API** — HTTP API for upload, download, list, delete
- **Streaming uploads** — Efficient handling of large files
- **File locking** — File-based locks for concurrent append-only operations
- **NDJSON support** — Append and stream newline-delimited JSON files
- **Auto-generated OpenAPI 3.1** — Full spec at `/openapi.json`, docs at `/docs`

AI Disclaimer: This project uses AI assistance for documentation creation as well as code generation for monotonous tasks. All architecture, design and more interesting code creation is done by a [human](https://x.com/MatSilva)

## Quick Start

### Prerequisites

- Go 1.24+

### Build & Run

```bash
# Run the server
make run

# Build binary
make build

# Run tests
make test
```

### Configuration

| Variable              | Description       | Default        |
| --------------------- | ----------------- | -------------- |
| `OBJECT_STORAGE_HOST` | Server host       | `0.0.0.0`      |
| `OBJECT_STORAGE_PORT` | Server port       | `8080`         |
| `STORAGE_BASE_PATH`   | Storage directory | `./bucket`     |
| `API_KEY`             | API key for auth  | `test-api-key` |
| `LOG_PRETTY`          | Pretty print logs | `false`        |

Copy `.env.example` to `.env` to configure locally.

### API Overview

| Method | Path                                          | Description               |
| ------ | --------------------------------------------- | ------------------------- |
| GET    | `/api/v1/files/list`                          | List all files for tenant |
| POST   | `/api/v1/files`                               | Upload file (stream)      |
| POST   | `/api/v1/files/form`                          | Upload file (multipart)   |
| GET    | `/api/v1/files/{objectId}`                    | Download file             |
| GET    | `/api/v1/files/metadata/{objectId}`           | Get file metadata         |
| DELETE | `/api/v1/files/{objectId}`                    | Delete file               |
| GET    | `/api/v1/files/checksum/{checksum}`           | Find file by checksum     |
| POST   | `/api/v1/files/{objectId}/ndjson/append/stream` | Append to NDJSON file   |
| GET    | `/api/v1/files/{objectId}/ndjson/items/stream`  | Stream NDJSON file      |

All file endpoints require headers: `x-api-key`, `x-tenant-id`, `x-user-id`

### OpenAPI

Full OpenAPI 3.1 spec: `GET /openapi.json`

Interactive docs: `GET /docs`

### Docker

```bash
make docker-build
```

### Deploy

```bash
make deploy
```

### Testing

```bash
# All tests
make test

# Specific test
make test-only T=TestUpload

# With coverage
make test-cover
```

## License

MIT License - see [LICENSE](LICENSE)
