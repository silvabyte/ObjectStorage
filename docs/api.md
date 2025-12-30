# API Reference

Base URL: `http://localhost:8080`

All file operation endpoints require [authentication headers](authentication.md).

## Health Endpoints

These endpoints do not require authentication.

### GET /

Root status check.

**Response:** `200 OK`
```json
{
  "status": "ok"
}
```

### GET /api/v1/ping

Simple connectivity check.

**Response:** `200 OK`
```json
{
  "message": "pong"
}
```

### GET /api/v1/health

Health check for monitoring systems.

**Response:** `200 OK`
```json
{
  "status": "healthy"
}
```

### GET /openapi

Auto-generated OpenAPI specification.

**Response:** `200 OK` with OpenAPI JSON

---

## File Operations

All endpoints below require authentication headers:
- `x-api-key` - API key
- `x-tenant-id` - Tenant identifier
- `x-user-id` - User identifier

### GET /api/v1/files/list

List all files for the authenticated tenant and user.

**Response:** `200 OK`
```json
{
  "files": [
    {
      "objectId": "550e8400-e29b-41d4-a716-446655440000",
      "bucket": "/bucket/tenant1/user1",
      "fileName": "document.pdf",
      "size": 102400,
      "mimeType": "application/pdf",
      "contentType": "application/pdf",
      "createdAt": "2024-01-15T10:30:00Z",
      "lastModified": "2024-01-15T10:30:00Z",
      "checksum": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      "etag": "\"e3b0c44298fc1c14\"",
      "metadata": {}
    }
  ],
  "status": "success"
}
```

---

### POST /api/v1/files

Upload a file using raw binary stream.

**Additional Headers:**
- `x-file-name` (required) - Original filename
- `x-mimetype` (optional) - MIME type

**Request Body:** Raw binary file content

**Response:** `201 Created` (new file) or `200 OK` (duplicate)
```json
{
  "file": {
    "objectId": "550e8400-e29b-41d4-a716-446655440000",
    "bucket": "/bucket/tenant1/user1",
    "fileName": "document.pdf",
    "size": 102400,
    "mimeType": "application/pdf",
    "contentType": "application/pdf",
    "createdAt": "2024-01-15T10:30:00Z",
    "lastModified": "2024-01-15T10:30:00Z",
    "checksum": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "etag": "\"e3b0c44298fc1c14\"",
    "metadata": {}
  },
  "status": "success"
}
```

**Errors:**
- `400 Bad Request` - Missing `x-file-name` header or upload failed

**Example:**
```bash
curl -X POST http://localhost:8080/api/v1/files \
  -H "x-api-key: test-api-key" \
  -H "x-tenant-id: tenant1" \
  -H "x-user-id: user1" \
  -H "x-file-name: document.pdf" \
  -H "x-mimetype: application/pdf" \
  --data-binary @document.pdf
```

---

### POST /api/v1/files/form

Upload a file using multipart form data.

**Content-Type:** `multipart/form-data`

**Form Fields:**
- `file` (required) - The file to upload

**Response:** `201 Created` (new file) or `200 OK` (duplicate)

Same response format as stream upload.

**Errors:**
- `400 Bad Request` - No file provided in form data

**Example:**
```bash
curl -X POST http://localhost:8080/api/v1/files/form \
  -H "x-api-key: test-api-key" \
  -H "x-tenant-id: tenant1" \
  -H "x-user-id: user1" \
  -F "file=@document.pdf"
```

---

### GET /api/v1/files/:objectId

Download file content by UUID.

**Path Parameters:**
- `objectId` - UUID of the file

**Response:** `200 OK` with file content

**Headers in Response:**
- `Content-Type` - MIME type of the file
- `Content-Disposition` - `attachment; filename="original-name.ext"`

**Errors:**
- `400 Bad Request` - Invalid UUID format
- `404 Not Found` - File does not exist

**Example:**
```bash
curl -X GET http://localhost:8080/api/v1/files/550e8400-e29b-41d4-a716-446655440000 \
  -H "x-api-key: test-api-key" \
  -H "x-tenant-id: tenant1" \
  -H "x-user-id: user1" \
  -o downloaded-file.pdf
```

---

### GET /api/v1/files/metadata/:objectId

Get metadata for a specific file.

**Path Parameters:**
- `objectId` - UUID of the file

**Response:** `200 OK`
```json
{
  "file": {
    "objectId": "550e8400-e29b-41d4-a716-446655440000",
    "bucket": "/bucket/tenant1/user1",
    "fileName": "document.pdf",
    "size": 102400,
    "mimeType": "application/pdf",
    "contentType": "application/pdf",
    "createdAt": "2024-01-15T10:30:00Z",
    "lastModified": "2024-01-15T10:30:00Z",
    "checksum": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "etag": "\"e3b0c44298fc1c14\"",
    "metadata": {}
  },
  "status": "success"
}
```

**Errors:**
- `400 Bad Request` - Invalid UUID format
- `404 Not Found` - File does not exist

---

### DELETE /api/v1/files/:objectId

Delete a file and its metadata.

**Path Parameters:**
- `objectId` - UUID of the file

**Response:** `200 OK`
```json
{
  "message": "File deleted",
  "status": "success"
}
```

**Errors:**
- `400 Bad Request` - Invalid UUID format
- `404 Not Found` - File does not exist

---

### GET /api/v1/files/checksum/:checksum

Find a file by its SHA-256 checksum. Useful for deduplication checks.

**Path Parameters:**
- `checksum` - SHA-256 checksum (64 hex characters)

**Response:** `200 OK`
```json
{
  "file": {
    "objectId": "550e8400-e29b-41d4-a716-446655440000",
    ...
  },
  "status": "success"
}
```

**Errors:**
- `404 Not Found` - No file with that checksum

---

### POST /api/v1/files/:objectId/ndjson/append/stream

Append content to an NDJSON file with locking.

**Path Parameters:**
- `objectId` - UUID of the file

**Request Body:** Raw binary content to append

**Response:** `200 OK`
```json
{
  "file": { ... },
  "status": "success"
}
```

**Errors:**
- `400 Bad Request` - Invalid UUID format
- `404 Not Found` - File does not exist
- `409 Conflict` - File is locked by another process

---

### GET /api/v1/files/:objectId/ndjson/items/stream

Stream NDJSON file items.

**Path Parameters:**
- `objectId` - UUID of the file

**Response:** `200 OK` with streamed content

**Errors:**
- `400 Bad Request` - Invalid UUID format
- `404 Not Found` - File does not exist

---

## Error Response Format

All errors return JSON:

```json
{
  "message": "Human-readable error description",
  "status": "error"
}
```

### HTTP Status Codes

| Status | Meaning |
|--------|---------|
| `200` | Success |
| `201` | Created (new file uploaded) |
| `400` | Bad Request (invalid input) |
| `401` | Unauthorized (auth failed) |
| `404` | Not Found |
| `409` | Conflict (file locked) |
