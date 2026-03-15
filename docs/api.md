# API Reference

ObjectStorage provides a REST API for multi-tenant file management. Full OpenAPI 3.1 spec is available at `GET /openapi.json` and interactive docs at `GET /docs`.

## Authentication

All file endpoints require these headers:

| Header        | Description                    |
| ------------- | ------------------------------ |
| `x-api-key`   | API key (must match `API_KEY`) |
| `x-tenant-id` | Tenant identifier              |
| `x-user-id`   | User identifier                |

## Health Endpoints

These endpoints require no authentication.

- `GET /` — `{"status": "ok"}`
- `GET /api/v1/ping` — `{"message": "pong"}`
- `GET /api/v1/health` — `{"status": "ok"}`

## File Endpoints

### List Files
`GET /api/v1/files/list`

### Upload File (Stream)
`POST /api/v1/files`

Headers: `x-file-name` (required), `x-mimetype` (optional)

Body: raw file content

Returns 201 for new files, 200 for duplicates (same SHA-256 checksum).

### Upload File (Multipart)
`POST /api/v1/files/form`

Body: `multipart/form-data` with `file` field

### Download File
`GET /api/v1/files/{objectId}`

### Get File Metadata
`GET /api/v1/files/metadata/{objectId}`

### Delete File
`DELETE /api/v1/files/{objectId}`

### Lookup by Checksum
`GET /api/v1/files/checksum/{checksum}`

## NDJSON Endpoints

### Append to NDJSON File
`POST /api/v1/files/{objectId}/ndjson/append/stream`

Body: raw data to append

### Stream NDJSON File
`GET /api/v1/files/{objectId}/ndjson/items/stream`
