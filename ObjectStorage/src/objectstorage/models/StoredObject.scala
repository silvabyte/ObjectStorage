package objectstorage.models

import upickle.default.{ReadWriter, macroRW, readwriter, write, read}
import java.time.Instant
import java.util.UUID

/** Upickle serialization helpers for common types */
object Picklers {
  // Instant serialization/deserialization
  given instantRW: ReadWriter[Instant] =
    readwriter[String].bimap[Instant](_.toString, Instant.parse)

  // UUID serialization/deserialization
  given uuidRW: ReadWriter[UUID] =
    readwriter[String].bimap[UUID](_.toString, UUID.fromString)
}

/** Represents a stored file object with metadata.
  *
  * @param objectId
  *   Unique identifier for the object as a UUID
  * @param bucket
  *   The bucket or container name (typically tenant/user path)
  * @param fileName
  *   Original file name (for reference)
  * @param size
  *   Size in bytes
  * @param mimeType
  *   MIME type of the object (e.g., "image/png", "application/pdf")
  * @param contentType
  *   Content type (same as MIME type, for compatibility)
  * @param createdAt
  *   When the object was first stored
  * @param lastModified
  *   Last time the object was modified
  * @param checksum
  *   SHA-256 checksum of the file content
  * @param etag
  *   Optional ETag for cache validation and integrity checks
  * @param metadata
  *   Additional user-defined metadata
  */
case class StoredObject(
    objectId: UUID,
    bucket: String,
    fileName: String,
    size: Long,
    mimeType: Option[String] = None,
    contentType: Option[String] = None,
    createdAt: Instant,
    lastModified: Instant,
    checksum: Option[String] = None,
    etag: Option[String] = None,
    metadata: Map[String, String] = Map.empty
)

object StoredObject {
  import Picklers.given

  given rw: ReadWriter[StoredObject] = macroRW

  def toJson(obj: StoredObject): String = write(obj)

  def fromJson(json: String): StoredObject = read[StoredObject](json)

  /** Create a new StoredObject with current timestamps */
  def create(
      bucket: String,
      fileName: String,
      size: Long,
      mimeType: Option[String] = None,
      checksum: Option[String] = None,
      metadata: Map[String, String] = Map.empty
  ): StoredObject = {
    val now = Instant.now()
    StoredObject(
      objectId = UUID.randomUUID(),
      bucket = bucket,
      fileName = fileName,
      size = size,
      mimeType = mimeType,
      contentType = mimeType,
      createdAt = now,
      lastModified = now,
      checksum = checksum,
      etag = checksum.map(c => s"\"$c\""),
      metadata = metadata
    )
  }
}
