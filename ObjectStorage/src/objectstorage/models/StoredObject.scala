package objectstorage.models

import upickle.default.{ReadWriter, readwriter, write, read}
import java.time.Instant
import java.util.UUID
import boogieloops.schema.derivation.Schematic
import boogieloops.schema.derivation.CollectionSchemas.given
import boogieloops.schema.bl

/** Upickle serialization helpers for common types */
object Picklers {
  // Instant serialization/deserialization
  given instantRW: ReadWriter[Instant] =
    readwriter[String].bimap[Instant](_.toString, Instant.parse)

  // UUID serialization/deserialization
  given uuidRW: ReadWriter[UUID] =
    readwriter[String].bimap[UUID](_.toString, UUID.fromString)

  // Schematic instances for custom types (maps to JSON Schema string with format)
  given uuidSchematic: Schematic[UUID] =
    Schematic.instance(bl.String(format = Some("uuid")))

  given instantSchematic: Schematic[Instant] =
    Schematic.instance(bl.String(format = Some("date-time")))
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
@Schematic.title("StoredObject")
@Schematic.description("Metadata about a stored file object")
case class StoredObject(
    @Schematic.description("Unique identifier for the object")
    objectId: UUID,
    @Schematic.description("The bucket or container path")
    bucket: String,
    @Schematic.description("Original file name")
    fileName: String,
    @Schematic.description("Size in bytes")
    @Schematic.minimum(0)
    size: Long,
    @Schematic.description("MIME type of the object")
    mimeType: Option[String] = None,
    @Schematic.description("Content type (same as MIME type)")
    contentType: Option[String] = None,
    @Schematic.description("When the object was first stored")
    createdAt: Instant,
    @Schematic.description("Last time the object was modified")
    lastModified: Instant,
    @Schematic.description("SHA-256 checksum of the file content")
    @Schematic.pattern("^[a-fA-F0-9]{64}$")
    checksum: Option[String] = None,
    @Schematic.description("ETag for cache validation")
    etag: Option[String] = None,
    @Schematic.description("Additional user-defined metadata")
    metadata: Map[String, String] = Map.empty
) derives Schematic, ReadWriter

object StoredObject {
  import Picklers.given

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
