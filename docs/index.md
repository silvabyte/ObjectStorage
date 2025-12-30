# ObjectStorage Documentation

ObjectStorage is a standalone object storage service built with Scala 3 and Cask. It provides multi-tenant file management with checksums, deduplication, and file locking for append operations.

## Table of Contents

- [API Reference](api.md) - Complete REST API documentation
- [Authentication](authentication.md) - API key and identity headers
- [Configuration](configuration.md) - Environment variables and settings
- [Storage](storage.md) - File organization, deduplication, and locking
- [Client SDK](client.md) - Scala HTTP client usage

## Quick Links

### Getting Started

```bash
# Run the server
./mill ObjectStorage.run

# Run tests
./mill ObjectStorage.test
```

### Make a Request

```bash
curl -X GET http://localhost:8080/api/v1/files/list \
  -H "x-api-key: test-api-key" \
  -H "x-tenant-id: my-tenant" \
  -H "x-user-id: my-user"
```

### Key Concepts

| Concept | Description |
|---------|-------------|
| **Multi-tenancy** | Files are isolated by tenant and user IDs |
| **Deduplication** | SHA-256 checksums prevent duplicate storage |
| **File Locking** | Append operations use file-based locks |
| **Streaming** | Large files are handled via streaming uploads |

## Architecture Overview

```
┌─────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   Client    │────>│   Cask Server    │────>│   FileManager   │
│             │     │   (Routes +      │     │   (Storage +    │
│             │<────│    Decorators)   │<────│    Checksums)   │
└─────────────┘     └──────────────────┘     └─────────────────┘
                            │
                            v
                    ┌──────────────────┐
                    │  FileLockManager │
                    │  (Append Locks)  │
                    └──────────────────┘
```

## Source Code

- `ObjectStorage/src/objectstorage/routes/` - HTTP endpoints
- `ObjectStorage/src/objectstorage/filemanager/` - File operations
- `ObjectStorage/src/objectstorage/decorators/` - Authentication middleware
- `ObjectStorage/src/objectstorage/models/` - Data models
- `ObjectStorage/src/objectstorage/config/` - Configuration
