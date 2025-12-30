# Configuration

ObjectStorage is configured via environment variables. Variables can be set directly or loaded from a `.env` file.

## Environment Variables

| Variable | Type | Default | Description |
|----------|------|---------|-------------|
| `OBJECT_STORAGE_HOST` | String | `0.0.0.0` | Server bind host |
| `OBJECT_STORAGE_PORT` | Int | `8080` | Server bind port |
| `STORAGE_BASE_PATH` | String | `./bucket` | Base directory for file storage |
| `API_KEY` | String | `test-api-key` | API key for authentication |
| `LOG_PRETTY` | Boolean | `false` | Enable colorized logs for development |
| `ENV_DIR` | String | Current directory | Directory containing `.env` file |

## .env File

Create a `.env` file in the project root (or directory specified by `ENV_DIR`):

```bash
# Server
OBJECT_STORAGE_HOST=0.0.0.0
OBJECT_STORAGE_PORT=8080

# Storage
STORAGE_BASE_PATH=/data/objectstorage

# Security
API_KEY=your-secure-api-key

# Logging
LOG_PRETTY=false
```

## Configuration Priority

1. System environment variables (highest priority)
2. `.env` file values
3. Default values (lowest priority)

## Server Configuration

### Host and Port

```bash
# Listen on all interfaces, port 8080 (default)
OBJECT_STORAGE_HOST=0.0.0.0
OBJECT_STORAGE_PORT=8080

# Listen only on localhost
OBJECT_STORAGE_HOST=127.0.0.1
OBJECT_STORAGE_PORT=3000
```

### Storage Path

```bash
# Default: relative to working directory
STORAGE_BASE_PATH=./bucket

# Production: absolute path
STORAGE_BASE_PATH=/var/lib/objectstorage/data
```

The storage directory will be created if it doesn't exist.

## Security Configuration

### API Key

**Important:** Change the default API key in production.

```bash
# Generate a secure key
openssl rand -hex 32

# Set in environment
API_KEY=a1b2c3d4e5f6...
```

## Logging Configuration

### Pretty Logs

Enable colorized, human-readable logs for development:

```bash
LOG_PRETTY=true
```

**Default (JSON):**
```json
{"timestamp":"2024-01-15T10:30:00.000Z","level":"INFO","message":"GET /api/v1/files/list 200 15.2ms"}
```

**Pretty:**
```
2024-01-15T10:30:00.000Z INFO GET /api/v1/files/list 200 15.2ms
```

## Docker Configuration

When running in Docker, use environment variables in `docker-compose.yml`:

```yaml
services:
  objectstorage:
    image: objectstorage
    environment:
      - OBJECT_STORAGE_HOST=0.0.0.0
      - OBJECT_STORAGE_PORT=8080
      - STORAGE_BASE_PATH=/data
      - API_KEY=${API_KEY}
      - LOG_PRETTY=false
    volumes:
      - ./data:/data
    ports:
      - "8080:8080"
```

Or use an env file:

```yaml
services:
  objectstorage:
    image: objectstorage
    env_file:
      - docker.env
```

## Programmatic Access

Configuration can be accessed in code via the `Config` object:

```scala
import objectstorage.config.Config

// Pre-defined values
val host = Config.HOST          // String
val port = Config.PORT          // Int
val storagePath = Config.STORAGE_BASE_PATH  // os.Path
val apiKey = Config.API_KEY     // String

// Custom values
val custom = Config.get("CUSTOM_VAR", "default")
val customInt = Config.getInt("CUSTOM_INT", 0)
val customBool = Config.getBoolean("CUSTOM_BOOL", false)
```
