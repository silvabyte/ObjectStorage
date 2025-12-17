package objectstorage.routes

import objectstorage.decorators.Decorators._
import objectstorage.filemanager._
import objectstorage.logging.Log
import objectstorage.models.StoredObject
import cask.model.Response
import scala.util.{Try, Success, Failure}
import java.util.UUID
import upickle.default._

/** REST API routes for file operations.
  *
  * Provides endpoints for upload, download, list, delete, and metadata operations.
  * All file operations require x-tenant-id and x-user-id headers.
  */
case class FileRoutes() extends cask.Routes {

  def apiPrefix = "/api/v1"

  /** Create a successful response with StoredObject metadata */
  def createFileStatResponse(so: StoredObject, code: Int = 200): Response[ujson.Obj] = {
    Response(
      ujson.Obj(
        "file"   -> read[ujson.Obj](StoredObject.toJson(so)),
        "status" -> "success"
      ),
      code
    )
  }

  /** Create an error response */
  def createErrorResponse(message: String, status: String, code: Int): Response[ujson.Obj] = {
    Response(ujson.Obj("message" -> message, "status" -> status), code)
  }

  /** List all files for a tenant and user */
  @withUserIdAndTenantId
  @cask.get(s"$apiPrefix/files/list")
  def listFilesMetaData()(tenantId: String, userId: String): Response[ujson.Obj] = {
    val fileListResult = FileManager.listFiles(tenantId, userId)
    Response(
      ujson.Obj(
        "files"  -> fileListResult.objects.map(so => read[ujson.Obj](StoredObject.toJson(so))),
        "status" -> fileListResult.status.toString
      ),
      200
    )
  }

  /** Get metadata for a single file using its UUID */
  @withUserIdAndTenantId
  @cask.get(s"$apiPrefix/files/metadata/:objectId")
  def getFileMetaData(objectId: String)(tenantId: String, userId: String): Response[ujson.Obj] = {
    Try(UUID.fromString(objectId)) match {
      case Failure(_) => createErrorResponse(s"Invalid objectId: $objectId", "error", 400)
      case Success(uuid) =>
        FileManager.getStoredObject(tenantId, userId, uuid) match {
          case Some(storedObject) => createFileStatResponse(storedObject)
          case None               => createErrorResponse(s"File $objectId not found", "error", 404)
        }
    }
  }

  /** Download file content using its UUID */
  @withUserIdAndTenantId
  @cask.get(s"$apiPrefix/files/:objectId")
  def downloadFile(
      request: cask.Request,
      objectId: String
  )(tenantId: String, userId: String): Response[geny.Readable | ujson.Obj] = {
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

  /** Upload a file using multipart form data */
  @withUserIdAndTenantId
  @cask.postForm(s"$apiPrefix/files/form")
  def createFileWithFormData(
      request: cask.Request,
      file: cask.FormFile
  )(tenantId: String, userId: String): Response[ujson.Obj] = {
    FileManager.uploadFile(tenantId, userId, file) match {
      case Right(storedObject) => createFileStatResponse(storedObject, 201)
      case Left(error) =>
        Log.error(s"Could not upload file ${file.fileName}", Map("error" -> error))
        createErrorResponse("Could not upload file", "error", 400)
    }
  }

  /** Upload a file using raw stream with filename in headers */
  @withUserIdAndTenantId
  @cask.post(s"$apiPrefix/files")
  def uploadFileStream(request: cask.Request)(tenantId: String, userId: String): Response[ujson.Obj] = {
    val context = Map("tenantId" -> tenantId, "userId" -> userId)
    val headers = request.headers
    headers.get("x-file-name").map(_.head) match {
      case None =>
        Log.error("No file name provided", context)
        createErrorResponse("No file name provided", "error", 400)
      case Some(fileName) =>
        val mimeType = headers.get("x-mimetype").map(_.head)
        Log.info(s"Uploading file ${fileName} with mime type ${mimeType}", context)
        FileManager.uploadFileStream(tenantId, userId, request.data, fileName, mimeType) match {
          case Right((code, storedObject)) =>
            Log.info(
              s"File uploaded with objectId ${storedObject.objectId}",
              context + ("storedObject" -> StoredObject.toJson(storedObject))
            )
            createFileStatResponse(storedObject, code)
          case Left((code, error)) =>
            Log.error(s"Could not upload file ${fileName}", context + ("error" -> error))
            createErrorResponse("Could not upload file", "error", code)
        }
    }
  }

  /** Delete a file using its UUID */
  @withUserIdAndTenantId
  @cask.delete(s"$apiPrefix/files/:objectId")
  def deleteFile(objectId: String)(tenantId: String, userId: String): Response[ujson.Obj] = {
    Try(UUID.fromString(objectId)) match {
      case Failure(_) => createErrorResponse(s"Invalid objectId: $objectId", "error", 400)
      case Success(uuid) =>
        FileManager.deleteFile(tenantId, userId, uuid) match {
          case Some(storedObject) => createFileStatResponse(storedObject, 200)
          case None               => createErrorResponse(s"File $objectId not found", "error", 404)
        }
    }
  }

  /** Lookup a file by its checksum */
  @withUserIdAndTenantId
  @cask.get(s"$apiPrefix/files/checksum/:checksum")
  def checkFileByChecksum(checksum: String)(
      tenantId: String,
      userId: String
  ): Response[ujson.Obj] = {
    FileManager.lookupByChecksum(tenantId, userId, checksum) match {
      case Some(storedObject) => createFileStatResponse(storedObject, 200)
      case None               => createErrorResponse("File not found", "not_found", 404)
    }
  }

  /** Append stream content to an NDJSON file */
  @withUserIdAndTenantId
  @cask.post(s"$apiPrefix/files/:objectId/ndjson/append/stream")
  def appendToFileStream(
      request: cask.Request,
      objectId: String
  )(tenantId: String, userId: String): Response[ujson.Obj] = {
    val context = Map("tenantId" -> tenantId, "userId" -> userId, "objectId" -> objectId)

    Try(UUID.fromString(objectId)) match {
      case Failure(_) =>
        Log.error(s"Invalid objectId format: $objectId", context)
        createErrorResponse(s"Invalid objectId: $objectId", "error", 400)
      case Success(uuid) =>
        Log.info(s"Appending stream content to file $objectId", context)

        FileManager.appendToFileStream(tenantId, userId, uuid, request.data) match {
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

  /** Stream NDJSON items as raw stream */
  @withUserIdAndTenantId
  @cask.get(s"$apiPrefix/files/:objectId/ndjson/items/stream")
  def streamFileItems(
      request: cask.Request,
      objectId: String
  )(tenantId: String, userId: String): Response[geny.Readable | ujson.Obj] = {
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

  /** Migrate files from old path pattern to new object ID-based system */
  @withUserIdAndTenantId
  @cask.post(s"$apiPrefix/files/migrate")
  def migrateFiles(request: cask.Request)(tenantId: String, userId: String): Response[ujson.Obj] = {
    FileManager.migrateFiles(tenantId, userId) match {
      case Right(_) =>
        Response(ujson.Obj("message" -> "Files migrated successfully", "status" -> "success"), 200)
      case Left(error) => createErrorResponse(error, "error", 400)
    }
  }

  initialize()
}
