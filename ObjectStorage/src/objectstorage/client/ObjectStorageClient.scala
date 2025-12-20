package objectstorage.client

import scala.util.{Try, Success, Failure}
import objectstorage.config.AppError
import objectstorage.models.StoredObject
import objectstorage.logging.Log
import requests._
import java.util.UUID
import upickle.default._

/**
 * HTTP client for ObjectStorage API.
 *
 * Provides methods for file upload, download, list, delete, and metadata operations.
 */
trait ObjectStorageApi {
  def listFiles(headers: Map[String, String] = Map.empty)(implicit
      checked: Boolean = true
  ): Either[AppError, Seq[StoredObject]]

  def uploadFile(file: geny.Readable, headers: Map[String, String] = Map.empty)(implicit
      checked: Boolean = true
  ): Either[AppError, StoredObject]

  def getFileMetadata(objectId: UUID, headers: Map[String, String] = Map.empty)(implicit
      checked: Boolean = true
  ): Either[AppError, StoredObject]

  def downloadFile(
      objectId: UUID,
      destination: String,
      headers: Map[String, String] = Map.empty
  )(implicit checked: Boolean = true): Either[AppError, Unit]

  def checkFileByChecksum(checksum: String, headers: Map[String, String] = Map.empty)(implicit
      checked: Boolean = true
  ): Either[AppError, Option[StoredObject]]

  def deleteFile(objectId: UUID, headers: Map[String, String] = Map.empty)(implicit
      checked: Boolean = true
  ): Either[AppError, Unit]
}

/**
 * Default implementation of ObjectStorageApi using HTTP requests.
 *
 * @param objectStorageUrl
 *   Base URL of the ObjectStorage service (e.g., "http://localhost:8080")
 */
case class ObjectStorageClient(objectStorageUrl: String) extends ObjectStorageApi {
  val baseUrl = s"${objectStorageUrl}/api/v1/files"

  def listFiles(
      headers: Map[String, String] = Map.empty
  )(implicit checked: Boolean = true): Either[AppError, Vector[StoredObject]] = {
    val response = requests.get(s"$baseUrl/list", headers = headers, check = checked)
    if (response.statusCode == 200) {
      val json = ujson.read(response.text())
      Right(read[Vector[StoredObject]](json("files").toString()))
    } else {
      Left(AppError(s"Failed to list files: ${response.statusCode}"))
    }
  }

  def uploadFile(file: geny.Readable, headers: Map[String, String] = Map.empty)(implicit
      checked: Boolean = true
  ): Either[AppError, StoredObject] = {
    val response = requests.post(
      s"$baseUrl",
      data = file,
      headers = headers,
      check = false
    )
    val text = response.text().trim()
    if (text.isEmpty()) {
      return Left(
        AppError(s"Received empty response from server with status code ${response.statusCode}")
      )
    }
    if (response.statusCode == 201 || response.statusCode == 200) {
      val json = ujson.read(text)
      Right(read[StoredObject](json("file").toString()))
    } else {
      Log.error(
        s"Failed to upload file",
        Map("status" -> response.statusCode.toString, "response" -> response.text())
      )
      Left(AppError(
        s"Failed to upload file: Status ${response.statusCode}, Response: ${response.text()}"
      ))
    }
  }

  def getFileMetadata(objectId: UUID, headers: Map[String, String] = Map.empty)(implicit
      checked: Boolean = true
  ): Either[AppError, StoredObject] = {
    val response = requests.get(s"$baseUrl/metadata/$objectId", headers = headers, check = checked)
    if (response.statusCode == 200) {
      val json = ujson.read(response.text())
      Right(read[StoredObject](json("file").toString()))
    } else {
      Left(AppError(s"Failed to get file metadata: ${response.statusCode}"))
    }
  }

  def downloadFile(
      objectId: UUID,
      destination: String,
      headers: Map[String, String] = Map.empty
  )(implicit checked: Boolean = true): Either[AppError, Unit] = {
    Log.info(s"Downloading file: $objectId to $destination")
    val stream = requests.get.stream(s"$baseUrl/$objectId", headers = headers, check = checked)
    Try(
      os.write.over(
        os.Path(destination),
        stream,
        createFolders = true
      )
    ) match {
      case Success(_) => Right(())
      case Failure(e) =>
        Log.error(s"Failed to download file", Map("error" -> e.getMessage))
        Left(AppError(s"Failed to download file: ${e.getMessage}"))
    }
  }

  def deleteFile(objectId: UUID, headers: Map[String, String] = Map.empty)(implicit
      checked: Boolean = true
  ): Either[AppError, Unit] = {
    val response = requests.delete(s"$baseUrl/$objectId", headers = headers, check = checked)
    if (response.statusCode == 200) {
      Right(())
    } else {
      Left(AppError(s"Failed to delete file: ${response.statusCode}"))
    }
  }

  def checkFileByChecksum(checksum: String, headers: Map[String, String] = Map.empty)(implicit
      checked: Boolean = true
  ): Either[AppError, Option[StoredObject]] = {
    val response = requests.get(s"$baseUrl/checksum/$checksum", headers = headers, check = checked)
    if (response.statusCode == 200) {
      val json = ujson.read(response.text())
      Right(Some(read[StoredObject](json("file").toString())))
    } else {
      Left(AppError(s"Failed to check file checksum: ${response.statusCode}"))
    }
  }
}
