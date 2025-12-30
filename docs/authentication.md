# Authentication

ObjectStorage uses a simple header-based authentication system with API key validation and tenant/user identity extraction.

## Required Headers

All file operation endpoints require these headers:

| Header | Required | Description |
|--------|----------|-------------|
| `x-api-key` | Yes | API key for authentication |
| `x-tenant-id` | Yes | Tenant identifier for multi-tenant isolation |
| `x-user-id` | Yes | User identifier within the tenant |

## API Key

The API key is validated against the `API_KEY` environment variable.

**Default:** `test-api-key` (for development/testing only)

**Production:** Set the `API_KEY` environment variable to a secure value.

```bash
export API_KEY="your-secure-api-key-here"
```

## Multi-Tenancy

Files are isolated by tenant and user. A request with:
- `x-tenant-id: acme-corp`
- `x-user-id: user-123`

Can only access files stored under that tenant/user combination. There is no cross-tenant or cross-user access.

## Example Request

```bash
curl -X GET http://localhost:8080/api/v1/files/list \
  -H "x-api-key: test-api-key" \
  -H "x-tenant-id: my-tenant" \
  -H "x-user-id: my-user"
```

## Authentication Errors

### Invalid or Missing API Key

**Status:** `401 Unauthorized`

```json
{
  "message": "Invalid API Key",
  "status": "unauthorized"
}
```

### Missing Identity Headers

**Status:** `401 Unauthorized`

```json
{
  "message": "Missing required headers: x-tenant-id, x-user-id",
  "status": "unauthorized"
}
```

## Decorator Architecture

Authentication is implemented using Cask decorators (middleware):

| Decorator | Purpose |
|-----------|---------|
| `@requireApiKey()` | Validates `x-api-key` header |
| `@withIdentity()` | Extracts tenant and user headers |
| `@withAuth()` | Combines both (used on all file endpoints) |

The `@withAuth()` decorator:
1. Validates the API key
2. Extracts tenant/user IDs
3. Creates an `AuthenticatedUser` object
4. Attaches it to the request for the handler to use

## Unauthenticated Endpoints

These endpoints do not require authentication:

- `GET /` - Root status
- `GET /api/v1/ping` - Connectivity check
- `GET /api/v1/health` - Health check
- `GET /openapi` - OpenAPI specification

## Security Considerations

1. **Always change the default API key** in production
2. **Use HTTPS** in production to protect headers in transit
3. **Rotate API keys** periodically
4. **Log authentication failures** are logged with client IP for auditing
