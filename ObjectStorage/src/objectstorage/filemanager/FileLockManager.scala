package objectstorage.filemanager

import os._
import scala.util.{Try, Success, Failure}
import java.time.Instant
import java.util.UUID
import upickle.default.{read, write, ReadWriter}
import objectstorage.logging.Log
import objectstorage.config.AppError

/** Context for a file lock, including process ID and expiration */
case class LockContext(
    processId: String,
    createdAt: Instant,
    timeoutMinutes: Int = 5
) {
  def isExpired: Boolean = {
    val expiredAt = createdAt.plusSeconds(timeoutMinutes * 60)
    Instant.now.isAfter(expiredAt)
  }
}

object LockContext {
  given instantRW: ReadWriter[Instant] = {
    upickle.default.readwriter[String].bimap[Instant](
      instant => instant.toString,
      str => Instant.parse(str)
    )
  }
  given rw: ReadWriter[LockContext] = upickle.default.macroRW[LockContext]
}

/** Context identifying a file for locking purposes */
case class FileContext(
    tenantId: String,
    userId: String,
    objectId: UUID,
    operation: String = "append"
) {
  def lockPath(basePath: Path): Path = {
    basePath / tenantId / userId / s"${objectId.toString}.lock"
  }

  def contextString: String = s"$tenantId/$userId/$objectId"
}

/** Distributed file locking manager using file-based locks.
  *
  * Provides atomic lock acquisition and release with automatic expiration.
  */
object FileLockManager {
  private val processId = UUID.randomUUID().toString
  private val lockBasePath = os.pwd / "bucket" / ".locks"

  // Ensure lock directory exists
  if (!os.exists(lockBasePath)) {
    os.makeDir.all(lockBasePath)
  }

  /** Attempt to acquire a lock for the given file context */
  def tryLock(fileContext: FileContext): Either[String, LockContext] = {
    val lockPath = fileContext.lockPath(lockBasePath)
    val lockDir = lockPath / os.up
    val context = Map(
      "fileContext" -> fileContext.contextString,
      "operation"   -> fileContext.operation,
      "processId"   -> processId
    )

    // Ensure lock directory exists for this tenant/user
    if (!os.exists(lockDir)) {
      Try(os.makeDir.all(lockDir)) match {
        case Failure(e) =>
          Log.error(s"Failed to create lock directory", context + ("error" -> e.getMessage))
          return Left("Failed to create lock directory")
        case Success(_) =>
          Log.info(s"Created lock directory", context + ("lockDir" -> lockDir.toString))
      }
    }

    val lockContext = LockContext(processId, Instant.now)
    val lockData = write(lockContext)
    val tempLockPath = (lockPath / os.up) / s"${lockPath.last}.tmp"

    Try {
      // Atomic lock creation using temp file + rename pattern
      os.write.over(tempLockPath, lockData, createFolders = true)

      // Attempt atomic rename - this will fail if lock already exists
      os.move(tempLockPath, lockPath)

      Log.info(s"Successfully acquired lock", context + ("lockPath" -> lockPath.toString))
      lockContext
    } match {
      case Success(lock) => Right(lock)
      case Failure(e) =>
        // Clean up temp file if it exists
        if (os.exists(tempLockPath)) {
          Try(os.remove(tempLockPath))
        }

        // Check if lock exists and get details for logging
        if (os.exists(lockPath)) {
          Try(read[LockContext](os.read(lockPath))) match {
            case Success(existingLock) =>
              Log.error(
                s"Lock acquisition failed - file is locked by another process",
                context + ("existingProcessId" -> existingLock.processId) +
                  ("existingCreatedAt" -> existingLock.createdAt.toString)
              )
              Left(s"File is currently locked by process ${existingLock.processId}")
            case Failure(_) =>
              Log.error(s"Lock acquisition failed - corrupted lock file", context)
              Left("File is locked with corrupted lock data")
          }
        } else {
          Log.error(s"Lock acquisition failed", context + ("error" -> e.getMessage))
          Left(s"Failed to acquire lock: ${e.getMessage}")
        }
    }
  }

  /** Release a lock for the given file context */
  def releaseLock(fileContext: FileContext): Either[String, Unit] = {
    val lockPath = fileContext.lockPath(lockBasePath)
    val context = Map(
      "fileContext" -> fileContext.contextString,
      "processId"   -> processId,
      "lockPath"    -> lockPath.toString
    )

    if (!os.exists(lockPath)) {
      Log.error(s"Cannot release lock - lock file does not exist", context)
      return Left("Lock file does not exist")
    }

    Try(read[LockContext](os.read(lockPath))) match {
      case Failure(e) =>
        Log.error(
          s"Cannot release lock - failed to read lock file",
          context + ("error" -> e.getMessage)
        )
        Left("Failed to read lock file")
      case Success(lockContext) =>
        if (lockContext.processId != processId) {
          Log.error(
            s"Cannot release lock - lock owned by different process",
            context + ("lockOwner" -> lockContext.processId)
          )
          Left(s"Lock is owned by different process: ${lockContext.processId}")
        } else {
          Try(os.remove(lockPath)) match {
            case Success(_) =>
              Log.info(s"Successfully released lock", context)
              Right(())
            case Failure(e) =>
              Log.error(s"Failed to remove lock file", context + ("error" -> e.getMessage))
              Left(s"Failed to remove lock file: ${e.getMessage}")
          }
        }
    }
  }

  /** Execute an operation while holding a lock */
  def withLock[T](fileContext: FileContext)(operation: => Either[String, T]): Either[String, T] = {
    val context = Map(
      "fileContext" -> fileContext.contextString,
      "operation"   -> fileContext.operation,
      "processId"   -> processId
    )

    Log.info(s"Attempting to acquire lock for operation", context)

    tryLock(fileContext) match {
      case Left(error) =>
        Log.error(s"Failed to acquire lock", context + ("error" -> error))
        Left(error)
      case Right(_) =>
        try {
          Log.info(s"Lock acquired, executing operation", context)
          val result = operation
          result match {
            case Left(operationError) =>
              Log.error(
                s"Operation failed while holding lock",
                context + ("operationError" -> operationError)
              )
            case Right(_) =>
              Log.info(s"Operation completed successfully", context)
          }
          result
        } finally {
          releaseLock(fileContext) match {
            case Left(releaseError) =>
              Log.error(
                s"Failed to release lock after operation",
                context + ("releaseError" -> releaseError)
              )
            case Right(_) =>
              Log.info(s"Lock released after operation", context)
          }
        }
    }
  }

  /** Clean up expired and corrupted lock files */
  def cleanupExpiredLocks(): Unit = {
    val context = Map("processId" -> processId, "lockBasePath" -> lockBasePath.toString)

    Log.info("Starting cleanup of expired locks", context)

    if (!os.exists(lockBasePath)) {
      Log.info("Lock base path does not exist, skipping cleanup", context)
      return
    }

    try {
      val lockFiles = os.walk(lockBasePath).filter(_.last.endsWith(".lock"))

      Log.info(s"Found ${lockFiles.length} lock files to check", context)

      val (cleanedCount, errorCount) = lockFiles.foldLeft((0, 0)) {
        case ((cleaned, errors), lockPath) =>
          val lockContext = Map("lockFile" -> lockPath.toString)

          Try(read[LockContext](os.read(lockPath))) match {
            case Failure(e) =>
              Log.error(
                s"Failed to read lock file, removing corrupted lock",
                lockContext + ("error" -> e.getMessage)
              )
              Try(os.remove(lockPath)) match {
                case Success(_) =>
                  Log.info(s"Removed corrupted lock file", lockContext)
                  (cleaned + 1, errors)
                case Failure(removeError) =>
                  Log.error(
                    s"Failed to remove corrupted lock file",
                    lockContext + ("removeError" -> removeError.getMessage)
                  )
                  (cleaned, errors + 1)
              }
            case Success(lockCtx) =>
              if (lockCtx.isExpired) {
                Log.info(
                  s"Found expired lock",
                  lockContext + ("lockProcessId" -> lockCtx.processId) + ("createdAt" -> lockCtx.createdAt.toString)
                )
                Try(os.remove(lockPath)) match {
                  case Success(_) =>
                    Log.info(
                      s"Removed expired lock",
                      lockContext + ("lockProcessId" -> lockCtx.processId)
                    )
                    (cleaned + 1, errors)
                  case Failure(removeError) =>
                    Log.error(
                      s"Failed to remove expired lock",
                      lockContext + ("removeError" -> removeError.getMessage)
                    )
                    (cleaned, errors + 1)
                }
              } else {
                (cleaned, errors)
              }
          }
      }

      Log.info(
        s"Lock cleanup completed",
        context + ("cleanedCount" -> cleanedCount) + ("errorCount" -> errorCount)
      )
    } catch {
      case e: Exception =>
        Log.error(s"Lock cleanup failed", context + ("error" -> e.getMessage))
    }
  }
}
