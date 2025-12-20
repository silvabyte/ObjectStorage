package objectstorage.routes

import objectstorage.filemanager._
import objectstorage.logging.Log
import objectstorage.models._
import cask.model.Response
import scala.util.{Try, Success, Failure}
import java.util.UUID
import upickle.default._
import boogieloops.web._
import boogieloops.web.Web.ValidatedRequestReader
import boogieloops.schema.derivation.Schematic
import boogieloops.schema.bl
import io.undertow.server.handlers.form.FormParserFactory

/** REST API routes for file operations.
  *
  * Provides endpoints for upload, download, list, delete, and metadata operations. All file operations require
  * x-tenant-id and x-user-id headers which are validated via schema.
  */
case class FileRoutes() extends cask.Routes {

  def apiPrefix = "/api/v1"

  // ============================================================================
  // Helper Methods
  // ============================================================================

  /** Extract tenant and user IDs from validated request headers. */
  private def extractTenantUser(r: ValidatedRequest): Either[Response[String], (String, String)] = {
    val tenantId = r.getHeader("x-tenant-id")
    val userId = r.getHeader("x-user-id")

    (tenantId, userId) match {
      case (Some(tid), Some(uid)) => Right((tid, uid))
      case (None, _) =>
        Left(
          Response(
            write(ErrorResponse("Request is missing required header: 'x-tenant-id'", "error")),
            400,
            Seq("Content-Type" -> "application/json")
          )
        )
      case (_, None) =>
        Left(
          Response(
            write(ErrorResponse("Request is missing required header: 'x-user-id'", "error")),
            400,
            Seq("Content-Type" -> "application/json")
          )
        )
    }
  }

  /** Create a successful response with StoredObject metadata */
  private def createFileStatResponse(so: StoredObject, code: Int = 200): Response[String] = {
    Response(
      write(FileResponse(so)),
      code,
      Seq("Content-Type" -> "application/json")
    )
  }

  /** Create an error response */
  private def createErrorResponse(message: String, status: String, code: Int): Response[String] = {
    Response(
      write(ErrorResponse(message, status)),
      code,
      Seq("Content-Type" -> "application/json")
    )
  }

  // ============================================================================
  // List & Metadata Endpoints
  // ============================================================================

  /** List all files for a tenant and user */
  @Web.get(
    s"$apiPrefix/files/list",
    RouteSchema(
      summary = Some("List all files"),
      description = Some("Returns a list of all files with metadata for the authenticated tenant and user"),
      tags = List("Files"),
      headers = Some(Schematic[TenantHeaders]),
      responses = Map(
        200 -> ApiResponse("List of files retrieved successfully", Schematic[ListFilesResponse]),
        400 -> ApiResponse("Missing required headers", Schematic[ErrorResponse])
      )
    )
  )
  def listFilesMetaData(r: ValidatedRequest): Response[String] = {
    extractTenantUser(r) match {
      case Left(errorResponse) => errorResponse
      case Right((tenantId, userId)) =>
        val fileListResult = FileManager.listFiles(tenantId, userId)
        Response(
          write(ListFilesResponse(fileListResult.objects.toList, fileListResult.status.toString)),
          200,
          Seq("Content-Type" -> "application/json")
        )
    }
  }

  /** Get metadata for a single file using its UUID */
  @Web.get(
    s"$apiPrefix/files/metadata/:objectId",
    RouteSchema(
      summary = Some("Get file metadata"),
      description = Some("Retrieve metadata for a specific file using its UUID"),
      tags = List("Metadata"),
      headers = Some(Schematic[TenantHeaders]),
      params = Some(Schematic[ObjectIdParam]),
      responses = Map(
        200 -> ApiResponse("File metadata retrieved successfully", Schematic[FileResponse]),
        400 -> ApiResponse("Invalid objectId format", Schematic[ErrorResponse]),
        404 -> ApiResponse("File not found", Schematic[ErrorResponse])
      )
    )
  )
  def getFileMetaData(objectId: String, r: ValidatedRequest): Response[String] = {
    extractTenantUser(r) match {
      case Left(errorResponse) => errorResponse
      case Right((tenantId, userId)) =>
        Try(UUID.fromString(objectId)) match {
          case Failure(_) => createErrorResponse(s"Invalid objectId: $objectId", "error", 400)
          case Success(uuid) =>
            FileManager.getStoredObject(tenantId, userId, uuid) match {
              case Some(storedObject) => createFileStatResponse(storedObject)
              case None               => createErrorResponse(s"File $objectId not found", "error", 404)
            }
        }
    }
  }

  // ============================================================================
  // Download Endpoint
  // ============================================================================

  /** Download file content using its UUID */
  @Web.get(
    s"$apiPrefix/files/:objectId",
    RouteSchema(
      summary = Some("Download file"),
      description = Some("Download the actual file content using its UUID"),
      tags = List("Files"),
      headers = Some(Schematic[TenantHeaders]),
      params = Some(Schematic[ObjectIdParam]),
      responses = Map(
        200 -> ApiResponse("File content", bl.String(format = Some("binary"))),
        400 -> ApiResponse("Invalid objectId format", Schematic[ErrorResponse]),
        404 -> ApiResponse("File not found", Schematic[ErrorResponse])
      )
    )
  )
  def downloadFile(objectId: String, r: ValidatedRequest): cask.Response.Raw = {
    extractTenantUser(r) match {
      case Left(errorResponse) => errorResponse
      case Right((tenantId, userId)) =>
        Try(UUID.fromString(objectId)) match {
          case Failure(_) => createErrorResponse(s"Invalid objectId: $objectId", "error", 400)
          case Success(uuid) =>
            FileManager.downloadFile(tenantId, userId, uuid) match {
              case Left(error) =>
                if (error.contains("Failed to download file"))
                  createErrorResponse(error, "error", 404)
                else
                  createErrorResponse(error, "error", 400)
              case Right(stream) => Response(stream, 200)
            }
        }
    }
  }

  // ============================================================================
  // Upload Endpoints
  // ============================================================================

  /** Upload a file using multipart form data */
  @Web.post(
    s"$apiPrefix/files/form",
    RouteSchema(
      summary = Some("Upload file using form data"),
      description = Some("Upload a file using multipart form data"),
      tags = List("Files"),
      headers = Some(Schematic[TenantHeaders]),
      responses = Map(
        201 -> ApiResponse("File uploaded successfully", Schematic[FileResponse]),
        400 -> ApiResponse("Upload failed", Schematic[ErrorResponse])
      )
    )
  )
  def createFileWithFormData(r: ValidatedRequest): Response[String] = {
    extractTenantUser(r) match {
      case Left(errorResponse) => errorResponse
      case Right((tenantId, userId)) =>
        // Parse multipart form data
        val parser = FormParserFactory.builder().build().createParser(r.original.exchange)
        val form = parser.parseBlocking()

        val fileItem = Option(form.getFirst("file"))
        fileItem match {
          case None =>
            createErrorResponse("No file provided in form data", "error", 400)
          case Some(formValue) if !formValue.isFileItem =>
            createErrorResponse("Expected file upload", "error", 400)
          case Some(formValue) =>
            val fileItem = formValue.getFileItem
            val fileName = formValue.getFileName
            val filePath = Option(fileItem.getFile)
            val headers = formValue.getHeaders

            // Create a cask.FormFile for FileManager
            val formFile = cask.FormFile(fileName, filePath, headers)

            FileManager.uploadFile(tenantId, userId, formFile) match {
              case Right(storedObject) => createFileStatResponse(storedObject, 201)
              case Left(error) =>
                Log.error(s"Could not upload file $fileName", Map("error" -> error))
                createErrorResponse("Could not upload file", "error", 400)
            }
        }
    }
  }

  /** Upload a file using raw stream with filename in headers */
  @Web.post(
    s"$apiPrefix/files",
    RouteSchema(
      summary = Some("Upload file using stream"),
      description = Some("Upload a file using raw binary stream with filename in headers"),
      tags = List("Files"),
      headers = Some(Schematic[UploadHeaders]),
      responses = Map(
        200 -> ApiResponse("File updated successfully", Schematic[FileResponse]),
        201 -> ApiResponse("File uploaded successfully", Schematic[FileResponse]),
        400 -> ApiResponse("Upload failed", Schematic[ErrorResponse])
      )
    )
  )
  def uploadFileStream(r: ValidatedRequest): Response[String] = {
    extractTenantUser(r) match {
      case Left(errorResponse) => errorResponse
      case Right((tenantId, userId)) =>
        val context = Map("tenantId" -> tenantId, "userId" -> userId)

        r.original.headers.get("x-file-name").flatMap(_.headOption) match {
          case None =>
            Log.error("No file name provided", context)
            createErrorResponse("No file name provided", "error", 400)
          case Some(fileName) =>
            val mimeType = r.original.headers.get("x-mimetype").flatMap(_.headOption)
            Log.info(s"Uploading file $fileName with mime type $mimeType", context)

            FileManager.uploadFileStream(tenantId, userId, r.original.data, fileName, mimeType) match {
              case Right((code, storedObject)) =>
                Log.info(
                  s"File uploaded with objectId ${storedObject.objectId}",
                  context + ("storedObject" -> StoredObject.toJson(storedObject))
                )
                createFileStatResponse(storedObject, code)
              case Left((code, error)) =>
                Log.error(s"Could not upload file $fileName", context + ("error" -> error))
                createErrorResponse("Could not upload file", "error", code)
            }
        }
    }
  }

  // ============================================================================
  // Delete Endpoint
  // ============================================================================

  /** Delete a file using its UUID */
  @Web.delete(
    s"$apiPrefix/files/:objectId",
    RouteSchema(
      summary = Some("Delete file"),
      description = Some("Delete a file and its metadata using its UUID"),
      tags = List("Files"),
      headers = Some(Schematic[TenantHeaders]),
      params = Some(Schematic[ObjectIdParam]),
      responses = Map(
        200 -> ApiResponse("File deleted successfully", Schematic[FileResponse]),
        400 -> ApiResponse("Invalid objectId format", Schematic[ErrorResponse]),
        404 -> ApiResponse("File not found", Schematic[ErrorResponse])
      )
    )
  )
  def deleteFile(objectId: String, r: ValidatedRequest): Response[String] = {
    extractTenantUser(r) match {
      case Left(errorResponse) => errorResponse
      case Right((tenantId, userId)) =>
        Try(UUID.fromString(objectId)) match {
          case Failure(_) => createErrorResponse(s"Invalid objectId: $objectId", "error", 400)
          case Success(uuid) =>
            FileManager.deleteFile(tenantId, userId, uuid) match {
              case Some(storedObject) => createFileStatResponse(storedObject, 200)
              case None               => createErrorResponse(s"File $objectId not found", "error", 404)
            }
        }
    }
  }

  // ============================================================================
  // Checksum Lookup Endpoint
  // ============================================================================

  /** Lookup a file by its checksum */
  @Web.get(
    s"$apiPrefix/files/checksum/:checksum",
    RouteSchema(
      summary = Some("Find file by checksum"),
      description = Some("Lookup a file by its SHA-256 checksum to check for duplicates"),
      tags = List("Files"),
      headers = Some(Schematic[TenantHeaders]),
      params = Some(Schematic[ChecksumParam]),
      responses = Map(
        200 -> ApiResponse("File found with matching checksum", Schematic[FileResponse]),
        404 -> ApiResponse("No file found with matching checksum", Schematic[ErrorResponse])
      )
    )
  )
  def checkFileByChecksum(checksum: String, r: ValidatedRequest): Response[String] = {
    extractTenantUser(r) match {
      case Left(errorResponse) => errorResponse
      case Right((tenantId, userId)) =>
        FileManager.lookupByChecksum(tenantId, userId, checksum) match {
          case Some(storedObject) => createFileStatResponse(storedObject, 200)
          case None               => createErrorResponse("File not found", "not_found", 404)
        }
    }
  }

  // ============================================================================
  // NDJSON Endpoints
  // ============================================================================

  /** Append stream content to an NDJSON file */
  @Web.post(
    s"$apiPrefix/files/:objectId/ndjson/append/stream",
    RouteSchema(
      summary = Some("Append to NDJSON file"),
      description = Some("Append stream content to an existing NDJSON file"),
      tags = List("Files"),
      headers = Some(Schematic[TenantHeaders]),
      params = Some(Schematic[ObjectIdParam]),
      responses = Map(
        200 -> ApiResponse("Content appended successfully", Schematic[FileResponse]),
        400 -> ApiResponse("Invalid request", Schematic[ErrorResponse]),
        404 -> ApiResponse("File not found", Schematic[ErrorResponse]),
        409 -> ApiResponse("File is locked", Schematic[ErrorResponse])
      )
    )
  )
  def appendToFileStream(objectId: String, r: ValidatedRequest): Response[String] = {
    extractTenantUser(r) match {
      case Left(errorResponse) => errorResponse
      case Right((tenantId, userId)) =>
        val context = Map("tenantId" -> tenantId, "userId" -> userId, "objectId" -> objectId)

        Try(UUID.fromString(objectId)) match {
          case Failure(_) =>
            Log.error(s"Invalid objectId format: $objectId", context)
            createErrorResponse(s"Invalid objectId: $objectId", "error", 400)
          case Success(uuid) =>
            Log.info(s"Appending stream content to file $objectId", context)

            FileManager.appendToFileStream(tenantId, userId, uuid, r.original.data) match {
              case Left(error) =>
                Log.error(s"Failed to append stream to file $objectId", context + ("error" -> error))
                if (error.contains("not found")) {
                  createErrorResponse(error, "error", 404)
                } else if (error.contains("locked") || error.contains("Lock")) {
                  createErrorResponse(error, "error", 409)
                } else {
                  createErrorResponse(error, "error", 400)
                }
              case Right(updatedObject) =>
                Log.info(
                  s"Successfully appended stream to file $objectId",
                  context + ("newSize" -> updatedObject.size.toString)
                )
                createFileStatResponse(updatedObject, 200)
            }
        }
    }
  }

  /** Stream NDJSON items as raw stream */
  @Web.get(
    s"$apiPrefix/files/:objectId/ndjson/items/stream",
    RouteSchema(
      summary = Some("Stream NDJSON items"),
      description = Some("Stream NDJSON file items as raw stream"),
      tags = List("Files"),
      headers = Some(Schematic[TenantHeaders]),
      params = Some(Schematic[ObjectIdParam]),
      responses = Map(
        200 -> ApiResponse("NDJSON stream", bl.String(format = Some("binary"))),
        400 -> ApiResponse("Invalid objectId format", Schematic[ErrorResponse]),
        404 -> ApiResponse("File not found", Schematic[ErrorResponse])
      )
    )
  )
  def streamFileItems(objectId: String, r: ValidatedRequest): cask.Response.Raw = {
    extractTenantUser(r) match {
      case Left(errorResponse) => errorResponse
      case Right((tenantId, userId)) =>
        Try(UUID.fromString(objectId)) match {
          case Failure(_) => createErrorResponse(s"Invalid objectId: $objectId", "error", 400)
          case Success(uuid) =>
            FileManager.downloadFile(tenantId, userId, uuid) match {
              case Left(error) =>
                if (error.contains("Failed to download file"))
                  createErrorResponse(error, "error", 404)
                else
                  createErrorResponse(error, "error", 400)
              case Right(stream) => Response(stream, 200)
            }
        }
    }
  }

  initialize()
}
