package objectstorage.filemanager

import os._
import scala.util.{Try, Success, Failure}
import java.io.{BufferedInputStream, FileInputStream}
import java.security.MessageDigest
import objectstorage.logging.Log
import objectstorage.config.AppError
import objectstorage.models.StoredObject
import java.util.UUID
import java.time.Instant
import upickle.default.{read, write}

/** Result status for object operations */
enum ObjectOperationResultStatus {
  case Success
  case Error(message: String)
}

/** Result of a single object operation */
case class ObjectOperationResult(storedObject: StoredObject, status: ObjectOperationResultStatus)

/** Result of listing objects */
case class ObjectListResult(objects: Vector[StoredObject], status: ObjectOperationResultStatus)

/** Core file management operations for object storage.
  *
  * Handles file upload, download, delete, and metadata management with checksum-based deduplication.
  */
object FileManager {
  // Base directory for storage (simulating a bucket system)
  private val storagePath = os.pwd / "bucket"
  // for storing the bucket path, like a key
  private val bucketPath = os.Path("/bucket")

  import TryOps._

  // Write binary data safely
  def writeFileSafe(filePath: Path, data: Array[Byte]): Either[AppError, Unit] = {
    safe { os.write.over(filePath, data, createFolders = true) }
  }

  def writeFileSafe(filePath: Path, stream: geny.Readable): Either[AppError, Unit] = {
    safe { os.write.over(filePath, stream, createFolders = true) }
  }

  def moveFileSafe(sourcePath: Path, targetPath: Path): Either[AppError, Unit] = {
    safe { os.move(sourcePath, targetPath) }
  }

  // Write text data safely
  def writeTextFileSafe(filePath: Path, data: String): Either[AppError, Unit] = {
    safe { os.write.over(filePath, data, createFolders = true) }
  }

  // Read text file safely
  def readTextFileSafe(filePath: Path): Either[AppError, String] = {
    safe { os.read(filePath) }
  }

  /** Generate SHA-256 checksum of a file */
  def computeChecksum(filePath: Path, bufferSize: Int = 8192): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val fileStream = new BufferedInputStream(new FileInputStream(filePath.toIO))

    try {
      val buffer = new Array[Byte](bufferSize)

      @scala.annotation.tailrec
      def processStream(): Unit = {
        val bytesRead = fileStream.read(buffer)
        if (bytesRead != -1) {
          digest.update(buffer, 0, bytesRead)
          processStream()
        }
      }

      processStream()
    } finally {
      fileStream.close()
    }

    digest.digest().map("%02x".format(_)).mkString
  }

  /** Lookup a file by its checksum */
  def lookupByChecksum(tenantId: String, userId: String, checksum: String): Option[StoredObject] = {
    val lookup = loadLookup(tenantId, userId)
    lookup.get(checksum).flatMap { objectId =>
      getStoredObject(tenantId, userId, objectId)
    }
  }

  def getUniqueId(lookup: Map[String, UUID]): UUID = {
    val existingIds = lookup.values.toSet
    Iterator.continually(UUID.randomUUID()).find(!existingIds.contains(_)).get
  }

  /** Load or initialize lookup.json */
  def loadLookup(tenantId: String, userId: String): Map[String, UUID] = {
    import objectstorage.models.Picklers.given
    val lookupFile = buildFolderPath(tenantId, userId) / "lookup.json"
    if (os.exists(lookupFile)) {
      Try(read[Map[String, UUID]](os.read(lookupFile))).getOrElse(Map.empty)
    } else {
      Map.empty
    }
  }

  /** Save lookup.json */
  def saveLookup(
      tenantId: String,
      userId: String,
      lookup: Map[String, UUID]
  ): Either[AppError, Unit] = {
    import objectstorage.models.Picklers.given
    val lookupFile = buildFolderPath(tenantId, userId) / "lookup.json"
    writeFileSafe(lookupFile, write(lookup))
  }

  /** List all stored objects by scanning for companion JSON metadata files */
  def listFiles(tenantId: String, userId: String): ObjectListResult = {
    import objectstorage.models.Picklers.given
    val folder = buildFolderPath(tenantId, userId)
    if (!os.exists(folder)) {
      Log.error(s"Folder does not exist for tenant $tenantId and user $userId")
      return ObjectListResult(
        Vector.empty,
        ObjectOperationResultStatus.Error("Folder does not exist")
      )
    }

    Log.info(s"Listing files in folder $folder")

    val metaFiles = os.list(folder).filter(f => os.isFile(f) && f.last.endsWith(".json")).toVector
    Log.info(s"Found ${metaFiles.length} metadata files")

    val objects = metaFiles.flatMap { metaFile =>
      readTextFileSafe(metaFile).toOption.flatMap { json =>
        Try(read[StoredObject](json)).toOption.orElse {
          Log.error(
            s"Failed to parse metadata for object $metaFile",
            Map("file" -> metaFile.toString())
          )
          None
        }
      }
    }

    ObjectListResult(objects, ObjectOperationResultStatus.Success)
  }

  /** Retrieve a stored object's metadata using its UUID */
  def getStoredObject(tenantId: String, userId: String, objectId: UUID): Option[StoredObject] = {
    import objectstorage.models.Picklers.given
    val folder = buildFolderPath(tenantId, userId)
    val metaFile = folder / s"${objectId.toString}.json"
    if (!os.exists(metaFile)) {
      Log.error(
        s"Metadata file does not exist for object $objectId",
        Map("tenantId" -> tenantId, "userId" -> userId)
      )
      return None
    }
    readTextFileSafe(metaFile).toOption.flatMap { json =>
      Try(read[StoredObject](json)).toOption.orElse {
        Log.error(
          s"Failed to parse metadata for object $objectId",
          Map("file" -> metaFile.toString())
        )
        None
      }
    }
  }

  /** Delete a file and its metadata */
  def deleteFile(tenantId: String, userId: String, objectId: UUID): Option[StoredObject] = {
    val folder = buildFolderPath(tenantId, userId)
    val contentFile = folder / objectId.toString
    val metaFile = folder / s"${objectId.toString}.json"

    if (!os.exists(contentFile) || !os.exists(metaFile)) {
      Log.error(
        s"Object or metadata file does not exist for object $objectId",
        Map("tenantId" -> tenantId, "userId" -> userId)
      )
      return None
    }

    val storedObjectOpt = getStoredObject(tenantId, userId, objectId)
    os.remove(contentFile)
    os.remove(metaFile)

    storedObjectOpt.foreach { _ =>
      val lookup = loadLookup(tenantId, userId)
      val updatedLookup = lookup.filterNot { case (_, id) => id == objectId }
      saveLookup(tenantId, userId, updatedLookup)
    }

    storedObjectOpt
  }

  /** Upload a file from a stream with deduplication */
  def uploadFileStream(
      tenantId: String,
      userId: String,
      stream: geny.Readable,
      fileName: String,
      mimeType: Option[String] = None
  ): Either[(Int, String), (Int, StoredObject)] = {
    val folder = buildFolderPath(tenantId, userId)
    val bucketPathStr = buildBucketPath(tenantId, userId)
    val lookup = loadLookup(tenantId, userId)

    // Save temporary file to calculate checksum
    val tempDir = os.temp.dir()
    val tempFilePath = tempDir / (UUID.randomUUID().toString + ".tmp")
    writeFileSafe(tempFilePath, stream) match {
      case Left(e) =>
        Log.error(s"Failed to write temporary file", Map("error" -> e.message))
        return Left((400, e.message))
      case Right(_) =>
        val checksum = computeChecksum(tempFilePath)
        // Check if file already exists
        lookup.get(checksum) match {
          case Some(existingObjectId) =>
            os.remove(tempFilePath)
            Log.info(
              s"Duplicate file detected, returning existing object",
              Map("checksum" -> checksum)
            )
            val storedObject = getStoredObject(tenantId, userId, existingObjectId).get
            return Right((200, storedObject))

          case None =>
            val objectId = getUniqueId(lookup)
            val contentFilePath = folder / objectId.toString
            val metadataFilePath = folder / s"${objectId.toString}.json"
            for {
              _ <- moveFileSafe(tempFilePath, contentFilePath).left.map(e => (400, e.message))
              fileSize = os.size(contentFilePath)
              now = Instant.now
              storedObject = StoredObject(
                objectId = objectId,
                bucket = bucketPathStr.toString,
                fileName = fileName,
                size = fileSize,
                mimeType = mimeType,
                contentType = mimeType,
                createdAt = now,
                lastModified = now,
                checksum = Some(checksum),
                etag = Some(checksum),
                metadata = Map.empty
              )
              _ <- writeFileSafe(metadataFilePath, StoredObject.toJson(storedObject)).left.map(e =>
                (400, e.message)
              )
              _ <- saveLookup(tenantId, userId, lookup + (checksum -> objectId)).left.map(e =>
                (400, e.message)
              )
              _ = Log.info(
                s"File uploaded successfully with objectId $objectId",
                Map("checksum" -> checksum)
              )
            } yield (201, storedObject)
        }
    }
  }

  /** Upload a file from form data */
  def uploadFile(
      tenantId: String,
      userId: String,
      file: cask.FormFile,
      mimeType: Option[String] = None
  ): Either[String, StoredObject] = {
    val folder = buildFolderPath(tenantId, userId)
    val bucketPathStr = buildBucketPath(tenantId, userId)
    val context = Map("tenantId" -> tenantId, "userId" -> userId)

    for {
      tempFilePath <- FileValidator.validateFormFile(Some(file))
      _ = Log.info(s"Uploading ${file.fileName} to $folder", context)
      objectId = UUID.randomUUID()
      contentFilePath = folder / objectId.toString
      metadataFilePath = folder / s"${objectId.toString}.json"
      _ <- writeFileSafe(contentFilePath, os.read.stream(tempFilePath)).left.map(_.message)
      fileSize = os.size(contentFilePath)
      now = Instant.now

      storedObject = StoredObject(
        objectId = objectId,
        bucket = bucketPathStr.toString,
        fileName = file.fileName,
        size = fileSize,
        mimeType = mimeType,
        contentType = mimeType,
        createdAt = now,
        lastModified = now,
        etag = None,
        metadata = Map.empty
      )
      _ <- writeTextFileSafe(metadataFilePath, StoredObject.toJson(storedObject)).left.map(_.message)
      _ = Log.info(
        s"File uploaded with objectId $objectId",
        context + ("metadataFile" -> metadataFilePath.toString)
      )
    } yield storedObject
  }

  /** Download file content as a stream */
  def downloadFile(
      tenantId: String,
      userId: String,
      objectId: UUID
  ): Either[String, geny.Readable] = {
    val folder = buildFolderPath(tenantId, userId)
    val contentFilePath = folder / objectId.toString
    Log.info(
      s"Downloading file for objectId $objectId",
      Map("filePath" -> contentFilePath.toString)
    )
    Try(os.read.stream(contentFilePath)) match {
      case Success(stream) => Right(stream)
      case Failure(e) =>
        Log.error(
          s"Failed to stream file: ${e.getMessage}",
          Map("filePath" -> contentFilePath.toString)
        )
        Left("Failed to download file")
    }
  }

  /** Append content to an existing file */
  def appendToFile(
      tenantId: String,
      userId: String,
      objectId: UUID,
      content: String
  ): Either[String, StoredObject] = {
    import objectstorage.models.Picklers.given
    val folder = buildFolderPath(tenantId, userId)
    val contentFilePath = folder / objectId.toString
    val metadataFilePath = folder / s"${objectId.toString}.json"
    val context = Map("tenantId" -> tenantId, "userId" -> userId, "objectId" -> objectId.toString)

    if (!os.exists(contentFilePath) || !os.exists(metadataFilePath)) {
      Log.error(s"File or metadata does not exist for objectId $objectId", context)
      return Left("File not found")
    }

    getStoredObject(tenantId, userId, objectId) match {
      case None =>
        Log.error(s"Could not load metadata for objectId $objectId", context)
        Left("Could not load file metadata")
      case Some(existingObject) =>
        Try {
          os.write.append(contentFilePath, content)
          val newSize = os.size(contentFilePath)
          val updatedObject = existingObject.copy(
            size = newSize,
            lastModified = java.time.Instant.now()
          )
          os.write.over(metadataFilePath, StoredObject.toJson(updatedObject))
          Log.info(s"Content appended to file $objectId", context + ("newSize" -> newSize.toString))
          updatedObject
        } match {
          case Success(updatedObject) => Right(updatedObject)
          case Failure(e) =>
            Log.error(s"Failed to append to file: ${e.getMessage}", context)
            Left("Failed to append content to file")
        }
    }
  }

  /** Append stream content to an existing file with locking */
  def appendToFileStream(
      tenantId: String,
      userId: String,
      objectId: UUID,
      stream: geny.Readable
  ): Either[String, StoredObject] = {
    val fileContext = FileContext(tenantId, userId, objectId, "append")

    FileLockManager.withLock(fileContext) {
      appendToFileStreamLocked(tenantId, userId, objectId, stream)
    }
  }

  private def appendToFileStreamLocked(
      tenantId: String,
      userId: String,
      objectId: UUID,
      stream: geny.Readable
  ): Either[String, StoredObject] = {
    import objectstorage.models.Picklers.given
    val folder = buildFolderPath(tenantId, userId)
    val contentFilePath = folder / objectId.toString
    val metadataFilePath = folder / s"${objectId.toString}.json"
    val context = Map("tenantId" -> tenantId, "userId" -> userId, "objectId" -> objectId.toString)

    if (!os.exists(contentFilePath) || !os.exists(metadataFilePath)) {
      Log.error(s"File or metadata does not exist for objectId $objectId", context)
      return Left("File not found")
    }

    getStoredObject(tenantId, userId, objectId) match {
      case None =>
        Log.error(s"Could not load metadata for objectId $objectId", context)
        Left("Could not load file metadata")
      case Some(existingObject) =>
        val tempFile = os.temp()
        writeFileSafe(tempFile, stream) match {
          case Left(error) =>
            Log.error(s"Failed to write stream to temp file: ${error.message}", context)
            Left(s"Failed to append stream content: ${error.message}")
          case Right(_) =>
            Try {
              val appendContent = os.read.bytes(tempFile)
              os.write.append(contentFilePath, appendContent)
              os.remove(tempFile)

              val newSize = os.size(contentFilePath)
              val updatedObject = existingObject.copy(
                size = newSize,
                lastModified = java.time.Instant.now(),
                checksum = Some(computeChecksum(contentFilePath)),
                etag = Some(computeChecksum(contentFilePath))
              )

              os.write.over(metadataFilePath, StoredObject.toJson(updatedObject))

              updatedObject.checksum.foreach { newChecksum =>
                val lookup = loadLookup(tenantId, userId)
                val updatedLookup = lookup.filter(_._2 != objectId) + (newChecksum -> objectId)
                saveLookup(tenantId, userId, updatedLookup)
              }

              Log.info(
                s"Stream content appended to file $objectId",
                context + ("newSize" -> newSize.toString)
              )
              updatedObject
            } match {
              case Success(updatedObject) => Right(updatedObject)
              case Failure(e) =>
                Log.error(s"Failed to append stream to file: ${e.getMessage}", context)
                Left(s"Failed to append stream content to file: ${e.getMessage}")
            }
        }
    }
  }

  def buildBucketPath(tenantId: String, userId: String): Path = {
    bucketPath / tenantId / userId
  }

  private def buildFolderPath(tenantId: String, userId: String): Path = {
    val folder = storagePath / tenantId / userId
    if (!os.exists(folder)) os.makeDir.all(folder)
    folder
  }

  /** Migrate files from old path pattern to new object ID-based system */
  def migrateFiles(tenantId: String, userId: String): Either[String, Unit] = {
    val folder = buildFolderPath(tenantId, userId)
    val backupFolder = folder / "backup"
    val files = os.list(folder).filter(f => os.isFile(f) && f.last.endsWith(".wav"))

    os.makeDir.all(backupFolder)
    files.foreach { file =>
      val backupFile = backupFolder / file.last
      os.copy(file, backupFile)
    }

    val backupFiles = os.list(backupFolder)
    if (backupFiles.isEmpty || backupFiles.length != files.length) {
      Log.error(s"Failed to backup migrate files", Map("tenantId" -> tenantId, "userId" -> userId))
      Log.info("Aborting migration")
      Left("Failed to backup migrate files")
    } else {
      val lookup = loadLookup(tenantId, userId)
      files.foreach { file =>
        val newObjectId = getUniqueId(lookup)
        val newContentFilePath = folder / newObjectId.toString
        val newMetadataFilePath = folder / s"${newObjectId.toString}.json"
        os.copy(file, newContentFilePath)
        val newStoredObject = StoredObject(
          objectId = newObjectId,
          bucket = bucketPath.toString,
          fileName = file.last,
          size = os.size(file),
          mimeType = Some("audio/wav"),
          contentType = Some("audio/wav"),
          createdAt = Instant.now,
          lastModified = Instant.now,
          checksum = Some(computeChecksum(newContentFilePath)),
          etag = None,
          metadata = Map.empty
        )
        writeFileSafe(newMetadataFilePath, StoredObject.toJson(newStoredObject))
      }

      Log.info(s"Files migrated successfully")
      Log.info(s"You can safely delete the backup folder ${backupFolder.toString}")
      Right(())
    }
  }
}

/** File validation utilities */
object FileValidator {
  def validateFormFile(file: Option[cask.FormFile]): Either[String, os.Path] = {
    file match {
      case None => Left("File validation failed: missing form file")
      case Some(f) =>
        (validateFileName(Option(f.fileName)), validateFilePath(f.filePath)) match {
          case (Left(error), _)          => Left(error)
          case (_, Left(error))          => Left(error)
          case (Right(_), Right(path)) => Right(path)
        }
    }
  }

  def validateFileName(fileName: Option[String]): Either[String, String] = {
    fileName match {
      case None | Some("") | Some("null") =>
        Left("File validation failed: missing file name")
      case Some(name) =>
        Right(name)
    }
  }

  def validateFilePath(filePath: Option[java.nio.file.Path]): Either[String, os.Path] = {
    filePath match {
      case Some(p) =>
        val resolvedPath = os.Path(p)
        Try(os.exists(resolvedPath)) match {
          case Success(_)   => Right(resolvedPath)
          case Failure(e) => Left(e.getMessage)
        }
      case None => Left("File validation failed: missing file path")
    }
  }
}

/** Try to Either conversion utilities */
object TryOps {
  extension [T](t: Try[T]) {
    def toAppEither: Either[AppError, T] = t.toEither.left.map(e => AppError(e.getMessage, Some(e)))
  }

  def safe[T](block: => T): Either[AppError, T] = Try(block).toAppEither
}
