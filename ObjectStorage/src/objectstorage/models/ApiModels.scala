package objectstorage.models

import boogieloops.schema.derivation.Schematic
import boogieloops.schema.derivation.CollectionSchemas.given
import upickle.default.ReadWriter
import Picklers.given

/**
 * API request and response models with Schematic derivation for OpenAPI generation and validation.
 *
 * These models define the contract for the ObjectStorage REST API.
 */

// ============================================================================
// Response Models
// ============================================================================

/** Standard response for successful file operations */
@Schematic.title("FileResponse")
@Schematic.description("Standard response for file operations")
case class FileResponse(
    @Schematic.description("The stored file object metadata")
    file: StoredObject,
    @Schematic.description("Operation status")
    status: String = "success"
) derives Schematic, ReadWriter

object FileResponse {
  import Picklers.given
}

/** Response for listing files */
@Schematic.title("ListFilesResponse")
@Schematic.description("Response containing a list of files")
case class ListFilesResponse(
    @Schematic.description("List of stored file objects")
    files: List[StoredObject],
    @Schematic.description("Operation status")
    status: String
) derives Schematic, ReadWriter

object ListFilesResponse {
  import Picklers.given
}

/** Standard error response */
@Schematic.title("ErrorResponse")
@Schematic.description("Standard error response")
case class ErrorResponse(
    @Schematic.description("Human-readable error message")
    message: String,
    @Schematic.description("Error status indicator")
    status: String
) derives Schematic, ReadWriter

/** Simple status response for health/ping endpoints */
@Schematic.title("StatusResponse")
@Schematic.description("Simple status response")
case class StatusResponse(
    @Schematic.description("Status indicator")
    status: String
) derives Schematic, ReadWriter

/** Ping response */
@Schematic.title("PingResponse")
@Schematic.description("Ping response")
case class PingResponse(
    @Schematic.description("Pong message")
    message: String = "pong"
) derives Schematic, ReadWriter

/** Success message response */
@Schematic.title("SuccessResponse")
@Schematic.description("Generic success response with message")
case class SuccessResponse(
    @Schematic.description("Success message")
    message: String,
    @Schematic.description("Status indicator")
    status: String = "success"
) derives Schematic, ReadWriter

// ============================================================================
// Header Models (for validation)
// ============================================================================

/** Required headers for tenant-scoped operations */
@Schematic.title("TenantHeaders")
@Schematic.description("Required headers for multi-tenant file operations")
case class TenantHeaders(
    @Schematic.description("Tenant identifier")
    @Schematic.minLength(1)
    `x-tenant-id`: String,
    @Schematic.description("User identifier")
    @Schematic.minLength(1)
    `x-user-id`: String
) derives Schematic

/** Headers for stream upload operations */
@Schematic.title("UploadHeaders")
@Schematic.description("Headers for stream upload operations")
case class UploadHeaders(
    @Schematic.description("Tenant identifier")
    @Schematic.minLength(1)
    `x-tenant-id`: String,
    @Schematic.description("User identifier")
    @Schematic.minLength(1)
    `x-user-id`: String,
    @Schematic.description("Name of the file being uploaded")
    @Schematic.minLength(1)
    `x-file-name`: String,
    @Schematic.description("MIME type of the file")
    `x-mimetype`: Option[String] = None
) derives Schematic

// ============================================================================
// Path Parameter Models (for validation)
// ============================================================================

/** Path parameter for object ID */
@Schematic.title("ObjectIdParam")
@Schematic.description("Path parameter containing object UUID")
case class ObjectIdParam(
    @Schematic.description("UUID of the file object")
    @Schematic.format("uuid")
    objectId: String
) derives Schematic

/** Path parameter for checksum lookup */
@Schematic.title("ChecksumParam")
@Schematic.description("Path parameter containing file checksum")
case class ChecksumParam(
    @Schematic.description("SHA-256 checksum of the file")
    @Schematic.pattern("^[a-fA-F0-9]{64}$")
    checksum: String
) derives Schematic
