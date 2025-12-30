# Storage Architecture

ObjectStorage uses a file-based storage system with multi-tenant isolation, checksum-based deduplication, and file locking for concurrent operations.

## Directory Structure

```
{STORAGE_BASE_PATH}/                    # Default: ./bucket
├── .locks/                             # Lock files for append operations
│   └── {tenantId}/
│       └── {userId}/
│           └── {objectId}.lock
└── {tenantId}/
    └── {userId}/
        ├── lookup.json                 # Checksum -> objectId mapping
        ├── {objectId}                  # File content (no extension)
        ├── {objectId}.json             # File metadata
        └── ...
```

### Example

For a file uploaded with:
- `x-tenant-id: acme-corp`
- `x-user-id: user-123`
- `x-file-name: report.pdf`

The storage would look like:

```
./bucket/
└── acme-corp/
    └── user-123/
        ├── lookup.json
        ├── 550e8400-e29b-41d4-a716-446655440000        # File content
        └── 550e8400-e29b-41d4-a716-446655440000.json   # Metadata
```

## File Naming

| File Type | Naming Convention |
|-----------|-------------------|
| Content | `{UUID}` (no extension) |
| Metadata | `{UUID}.json` |
| Lock | `{UUID}.lock` |
| Lookup | `lookup.json` (per tenant/user) |

File content is stored without extensions. The original filename is preserved in the metadata.

## Metadata Format

Each file has a corresponding `.json` metadata file:

```json
{
  "objectId": "550e8400-e29b-41d4-a716-446655440000",
  "bucket": "/bucket/acme-corp/user-123",
  "fileName": "report.pdf",
  "size": 102400,
  "mimeType": "application/pdf",
  "contentType": "application/pdf",
  "createdAt": "2024-01-15T10:30:00Z",
  "lastModified": "2024-01-15T10:30:00Z",
  "checksum": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "etag": "\"e3b0c44298fc1c14\"",
  "metadata": {}
}
```

## Deduplication

ObjectStorage uses SHA-256 checksums to prevent duplicate file storage.

### How It Works

1. **Upload initiated** - File content is written to a temporary location
2. **Checksum computed** - SHA-256 hash is calculated
3. **Lookup check** - `lookup.json` is checked for existing checksum
4. **If duplicate found:**
   - Return existing `StoredObject`
   - Response status: `200 OK`
   - Temp file is discarded
5. **If new file:**
   - Move temp file to permanent location
   - Create metadata JSON
   - Update `lookup.json`
   - Response status: `201 Created`

### lookup.json Format

```json
{
  "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855": "550e8400-e29b-41d4-a716-446655440000",
  "a1b2c3d4...": "660f9500-f30c-52e5-b827-557766551111"
}
```

Maps checksum to objectId for O(1) duplicate lookup.

### Checksum Lookup API

Check if a file exists by checksum:

```bash
curl http://localhost:8080/api/v1/files/checksum/e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855 \
  -H "x-api-key: test-api-key" \
  -H "x-tenant-id: acme-corp" \
  -H "x-user-id: user-123"
```

## File Locking

File-based locks enable safe concurrent append operations on NDJSON files.

### Lock File Format

```json
{
  "processId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "createdAt": "2024-01-15T10:30:00Z",
  "timeoutSeconds": 30
}
```

### Lock Lifecycle

1. **Acquire** - Create lock file atomically (temp file + rename)
2. **Validate** - Check if existing lock is expired or corrupted
3. **Operation** - Perform append operation
4. **Release** - Delete lock file

### Self-Healing

The lock manager automatically handles:

- **Expired locks** - Removed if older than `timeoutSeconds`
- **Corrupted locks** - Removed if JSON is invalid
- **Orphaned locks** - Removed if process is no longer running

### Lock Conflict Response

If a lock is held by another process:

**Status:** `409 Conflict`

```json
{
  "message": "File is currently locked by process a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "error"
}
```

## Multi-Tenant Isolation

Files are completely isolated between tenants and users:

- **Tenant A, User 1** cannot access files from **Tenant A, User 2**
- **Tenant A** cannot access files from **Tenant B**
- Directory paths enforce isolation at the filesystem level

## Storage Limits

There are no built-in storage limits. Implement external monitoring for:

- Disk usage per tenant
- File count per user
- Individual file size limits

## Backup Considerations

The entire `STORAGE_BASE_PATH` directory can be backed up as a unit. The file-based approach means:

- Standard filesystem backup tools work
- Incremental backups are efficient (only changed files)
- Point-in-time recovery is possible
- No database to coordinate
