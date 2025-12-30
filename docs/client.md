# Scala Client SDK

ObjectStorage includes a Scala HTTP client for programmatic access.

## Installation

The client is included in the ObjectStorage package. Import it:

```scala
import objectstorage.client.{ObjectStorageClient, ObjectStorageApi}
import objectstorage.models.StoredObject
import java.util.UUID
```

## Creating a Client

```scala
val client = ObjectStorageClient("http://localhost:8080")
```

## Authentication Headers

All methods require authentication headers:

```scala
val headers = Map(
  "x-api-key" -> "your-api-key",
  "x-tenant-id" -> "tenant-123",
  "x-user-id" -> "user-456"
)
```

## Methods

### List Files

List all files for the authenticated tenant/user.

```scala
client.listFiles(headers) match {
  case Right(files) => 
    files.foreach(f => println(s"${f.objectId}: ${f.fileName}"))
  case Left(error) => 
    println(s"Error: ${error.message}")
}
```

**Returns:** `Either[AppError, Seq[StoredObject]]`

### Upload File

Upload a file using streaming.

```scala
val uploadHeaders = headers ++ Map(
  "x-file-name" -> "document.pdf",
  "x-mimetype" -> "application/pdf"  // optional
)

val file = os.read.stream(os.Path("/path/to/file.pdf"))

client.uploadFile(file, uploadHeaders) match {
  case Right(stored) => 
    println(s"Uploaded: ${stored.objectId}")
    println(s"Checksum: ${stored.checksum}")
  case Left(error) => 
    println(s"Error: ${error.message}")
}
```

**Returns:** `Either[AppError, StoredObject]`

**Note:** Returns `Right` for both `201 Created` (new file) and `200 OK` (duplicate).

### Get File Metadata

Retrieve metadata for a specific file.

```scala
val objectId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")

client.getFileMetadata(objectId, headers) match {
  case Right(stored) => 
    println(s"File: ${stored.fileName}")
    println(s"Size: ${stored.size} bytes")
    println(s"Created: ${stored.createdAt}")
  case Left(error) => 
    println(s"Error: ${error.message}")
}
```

**Returns:** `Either[AppError, StoredObject]`

### Download File

Download file content to a local path.

```scala
val objectId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
val destination = "/path/to/download/file.pdf"

client.downloadFile(objectId, destination, headers) match {
  case Right(_) => 
    println(s"Downloaded to: $destination")
  case Left(error) => 
    println(s"Error: ${error.message}")
}
```

**Returns:** `Either[AppError, Unit]`

The destination directory is created if it doesn't exist.

### Check File by Checksum

Check if a file exists with the given SHA-256 checksum.

```scala
val checksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

client.checkFileByChecksum(checksum, headers) match {
  case Right(Some(stored)) => 
    println(s"File exists: ${stored.objectId}")
  case Right(None) => 
    println("File not found")
  case Left(error) => 
    println(s"Error: ${error.message}")
}
```

**Returns:** `Either[AppError, Option[StoredObject]]`

### Delete File

Delete a file and its metadata.

```scala
val objectId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")

client.deleteFile(objectId, headers) match {
  case Right(_) => 
    println("File deleted")
  case Left(error) => 
    println(s"Error: ${error.message}")
}
```

**Returns:** `Either[AppError, Unit]`

## Complete Example

```scala
import objectstorage.client.ObjectStorageClient
import java.util.UUID

object Example extends App {
  val client = ObjectStorageClient("http://localhost:8080")
  
  val headers = Map(
    "x-api-key" -> "test-api-key",
    "x-tenant-id" -> "my-tenant",
    "x-user-id" -> "my-user"
  )
  
  // Upload a file
  val uploadHeaders = headers ++ Map("x-file-name" -> "test.txt")
  val content = os.read.stream(os.pwd / "test.txt")
  
  val uploadResult = client.uploadFile(content, uploadHeaders)
  
  uploadResult match {
    case Right(stored) =>
      println(s"Uploaded: ${stored.objectId}")
      
      // Get metadata
      client.getFileMetadata(stored.objectId, headers).foreach { meta =>
        println(s"Size: ${meta.size} bytes")
      }
      
      // Download
      client.downloadFile(stored.objectId, "/tmp/downloaded.txt", headers)
      
      // Delete
      client.deleteFile(stored.objectId, headers)
      println("Cleaned up")
      
    case Left(error) =>
      println(s"Upload failed: ${error.message}")
  }
}
```

## Error Handling

All methods return `Either[AppError, T]`:

- `Right(value)` - Success
- `Left(AppError(message))` - Failure

The `AppError` case class contains:
- `message: String` - Human-readable error description
- `cause: Option[Throwable]` - Optional underlying exception

## API Trait

For testing or custom implementations, use the `ObjectStorageApi` trait:

```scala
trait ObjectStorageApi {
  def listFiles(headers: Map[String, String]): Either[AppError, Seq[StoredObject]]
  def uploadFile(file: geny.Readable, headers: Map[String, String]): Either[AppError, StoredObject]
  def getFileMetadata(objectId: UUID, headers: Map[String, String]): Either[AppError, StoredObject]
  def downloadFile(objectId: UUID, destination: String, headers: Map[String, String]): Either[AppError, Unit]
  def checkFileByChecksum(checksum: String, headers: Map[String, String]): Either[AppError, Option[StoredObject]]
  def deleteFile(objectId: UUID, headers: Map[String, String]): Either[AppError, Unit]
}
```
