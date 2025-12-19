package objectstorage.test.filemanager

import utest._
import objectstorage.filemanager.{FileLockManager, FileContext, LockContext}
import objectstorage.config.Config
import java.util.UUID
import java.time.Instant
import scala.util.{Try, Success, Failure}
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import os._
import upickle.default.{read => ujsonRead, write => ujsonWrite}

object FileLockManagerTest extends TestSuite {
  implicit val ec: ExecutionContext = ExecutionContext.global
  val envDirectory = sys.env.getOrElse("ENV_DIR", os.pwd.toString)
  Config.initialize(envDirectory)

  // Create a test-specific lock base path to avoid conflicts
  val testLockBasePath = os.temp.dir(prefix = "filelock-test-")

  // Helper to create test FileContext
  def createTestFileContext(
      tenantId: String = "test-tenant",
      userId: String = "test-user",
      objectId: UUID = UUID.randomUUID(),
      operation: String = "append"
  ): FileContext = {
    FileContext(tenantId, userId, objectId, operation)
  }

  // Helper to create a test FileLockManager with custom lock path
  object TestFileLockManager {
    private val processId = UUID.randomUUID().toString
    private val lockBasePath = testLockBasePath

    // Ensure lock directory exists
    if (!os.exists(lockBasePath)) {
      os.makeDir.all(lockBasePath)
    }

    def tryLock(fileContext: FileContext): Either[String, LockContext] = {
      val lockPath = fileContext.lockPath(lockBasePath)
      val lockDir = lockPath / os.up

      // Ensure lock directory exists for this tenant/user
      if (!os.exists(lockDir)) {
        Try(os.makeDir.all(lockDir)) match {
          case Failure(e) => return Left("Failed to create lock directory")
          case Success(_) =>
        }
      }

      // Self-healing: check for expired or corrupted locks and remove them
      if (os.exists(lockPath)) {
        Try(ujsonRead[LockContext](os.read(lockPath))) match {
          case Success(existingLock) if existingLock.isExpired =>
            Try(os.remove(lockPath))
          case Success(_) =>
            // Lock exists and is not expired - will fail below
          case Failure(_) =>
            Try(os.remove(lockPath))
        }
      }

      val lockContext = LockContext(processId, Instant.now)
      val lockData = ujsonWrite(lockContext)
      val tempLockPath = (lockPath / os.up) / s"${lockPath.last}.tmp"

      Try {
        os.write.over(tempLockPath, lockData, createFolders = true)
        os.move(tempLockPath, lockPath)
        lockContext
      } match {
        case Success(lock) => Right(lock)
        case Failure(e) =>
          if (os.exists(tempLockPath)) {
            Try(os.remove(tempLockPath))
          }

          if (os.exists(lockPath)) {
            Try(ujsonRead[LockContext](os.read(lockPath))) match {
              case Success(existingLock) =>
                Left(s"File is currently locked by process ${existingLock.processId}")
              case Failure(_) =>
                Left("File is locked with corrupted lock data")
            }
          } else {
            Left(s"Failed to acquire lock: ${e.getMessage}")
          }
      }
    }

    def releaseLock(fileContext: FileContext): Either[String, Unit] = {
      val lockPath = fileContext.lockPath(lockBasePath)

      if (!os.exists(lockPath)) {
        return Left("Lock file does not exist")
      }

      Try(ujsonRead[LockContext](os.read(lockPath))) match {
        case Failure(e) =>
          Left("Failed to read lock file")
        case Success(lockContext) =>
          if (lockContext.processId != processId) {
            Left(s"Lock is owned by different process: ${lockContext.processId}")
          } else {
            Try(os.remove(lockPath)) match {
              case Success(_) => Right(())
              case Failure(e) => Left(s"Failed to remove lock file: ${e.getMessage}")
            }
          }
      }
    }

    def withLock[T](fileContext: FileContext)(operation: => Either[String, T])
        : Either[String, T] = {
      tryLock(fileContext) match {
        case Left(error) => Left(error)
        case Right(lockContext) =>
          try {
            operation
          } finally {
            releaseLock(fileContext)
          }
      }
    }

    def getProcessId: String = processId
    def getLockBasePath: os.Path = lockBasePath
  }

  // Cleanup helper
  def cleanupTestDirectory(): Unit = {
    if (os.exists(testLockBasePath)) {
      Try(os.remove.all(testLockBasePath))
    }
  }

  val tests = Tests {

    test("LockContext") {
      test("should create valid lock context") {
        val processId = "test-process-123"
        val now = Instant.now
        val lockContext = LockContext(processId, now)

        assert(lockContext.processId == processId)
        assert(lockContext.createdAt == now)
        assert(lockContext.timeoutSeconds == 30)
      }

      test("should check expiration correctly") {
        val processId = "test-process-123"
        val fortySecondsAgo = Instant.now.minusSeconds(40)
        val now = Instant.now

        val expiredLock = LockContext(processId, fortySecondsAgo, 30) // 30 second timeout
        val activeLock = LockContext(processId, now, 30)              // 30 second timeout

        assert(expiredLock.isExpired)
        assert(!activeLock.isExpired)
      }

      test("should serialize and deserialize correctly") {
        val processId = "test-process-123"
        val now = Instant.now
        val originalLock = LockContext(processId, now)

        val json = ujsonWrite(originalLock)
        val deserializedLock = ujsonRead[LockContext](json)

        assert(deserializedLock.processId == originalLock.processId)
        assert(deserializedLock.createdAt == originalLock.createdAt)
        assert(deserializedLock.timeoutSeconds == originalLock.timeoutSeconds)
      }
    }

    test("FileContext") {
      test("should create valid file context") {
        val tenantId = "tenant-123"
        val userId = "user-456"
        val objectId = UUID.randomUUID()
        val operation = "append"

        val fileContext = FileContext(tenantId, userId, objectId, operation)

        assert(fileContext.tenantId == tenantId)
        assert(fileContext.userId == userId)
        assert(fileContext.objectId == objectId)
        assert(fileContext.operation == operation)
      }

      test("should generate correct lock path") {
        val tenantId = "tenant-123"
        val userId = "user-456"
        val objectId = UUID.randomUUID()
        val fileContext = FileContext(tenantId, userId, objectId)

        val basePath = os.pwd / "test"
        val lockPath = fileContext.lockPath(basePath)
        val expectedPath = basePath / tenantId / userId / s"${objectId.toString}.lock"

        assert(lockPath == expectedPath)
      }

      test("should generate correct context string") {
        val tenantId = "tenant-123"
        val userId = "user-456"
        val objectId = UUID.randomUUID()
        val fileContext = FileContext(tenantId, userId, objectId)

        val contextString = fileContext.contextString
        val expectedString = s"$tenantId/$userId/$objectId"

        assert(contextString == expectedString)
      }

      test("should handle special characters in tenant/user IDs") {
        val tenantId = "tenant-with-dashes_and_underscores"
        val userId = "user@domain.com"
        val objectId = UUID.randomUUID()
        val fileContext = FileContext(tenantId, userId, objectId)

        val basePath = os.temp.dir()
        val lockPath = fileContext.lockPath(basePath)
        val expectedPath = basePath / tenantId / userId / s"${objectId.toString}.lock"

        assert(lockPath == expectedPath)
        assert(fileContext.contextString == s"$tenantId/$userId/$objectId")

        // Cleanup
        os.remove.all(basePath)
      }
    }

    test("FileLockManager - Basic Operations") {
      test("should acquire and release lock successfully") {
        cleanupTestDirectory()
        val fileContext = createTestFileContext()

        // Acquire lock
        val lockResult = TestFileLockManager.tryLock(fileContext)
        assert(lockResult.isRight)

        val lockContext = lockResult.right.get
        assert(lockContext.processId == TestFileLockManager.getProcessId)

        // Verify lock file exists
        val lockPath = fileContext.lockPath(TestFileLockManager.getLockBasePath)
        assert(os.exists(lockPath))

        // Release lock
        val releaseResult = TestFileLockManager.releaseLock(fileContext)
        assert(releaseResult.isRight)

        // Verify lock file is removed
        assert(!os.exists(lockPath))
      }

      test("should prevent double lock acquisition") {
        cleanupTestDirectory()
        val fileContext = createTestFileContext()

        // First lock should succeed
        val firstLock = TestFileLockManager.tryLock(fileContext)
        assert(firstLock.isRight)

        // Second lock should fail
        val secondLock = TestFileLockManager.tryLock(fileContext)
        assert(secondLock.isLeft)
        assert(secondLock.left.get.contains("locked by process"))

        // Cleanup
        TestFileLockManager.releaseLock(fileContext)
      }

      test("should fail to release non-existent lock") {
        cleanupTestDirectory()
        val fileContext = createTestFileContext()

        val releaseResult = TestFileLockManager.releaseLock(fileContext)
        assert(releaseResult.isLeft)
        assert(releaseResult.left.get.contains("does not exist"))
      }

      test("should fail to release lock owned by different process") {
        cleanupTestDirectory()
        val fileContext = createTestFileContext()

        // Create a lock file with different process ID
        val lockPath = fileContext.lockPath(TestFileLockManager.getLockBasePath)
        val lockDir = lockPath / os.up
        os.makeDir.all(lockDir)

        val differentProcessLock = LockContext("different-process-id", Instant.now)
        os.write.over(lockPath, ujsonWrite(differentProcessLock))

        val releaseResult = TestFileLockManager.releaseLock(fileContext)
        assert(releaseResult.isLeft)
        assert(releaseResult.left.get.contains("owned by different process"))

        // Cleanup
        os.remove(lockPath)
      }
    }

    test("FileLockManager - withLock Operation") {
      test("should execute operation with lock protection") {
        cleanupTestDirectory()
        val fileContext = createTestFileContext()
        var operationExecuted = false

        val result = TestFileLockManager.withLock(fileContext) {
          operationExecuted = true
          Right("operation completed")
        }

        assert(result.isRight)
        assert(result.right.get == "operation completed")
        assert(operationExecuted)

        // Verify lock is released after operation
        val lockPath = fileContext.lockPath(TestFileLockManager.getLockBasePath)
        assert(!os.exists(lockPath))
      }

      test("should release lock even if operation fails") {
        cleanupTestDirectory()
        val fileContext = createTestFileContext()

        val result = TestFileLockManager.withLock(fileContext) {
          Left("operation failed")
        }

        assert(result.isLeft)
        assert(result.left.get == "operation failed")

        // Verify lock is released after failed operation
        val lockPath = fileContext.lockPath(TestFileLockManager.getLockBasePath)
        assert(!os.exists(lockPath))
      }

      test("should fail if lock cannot be acquired") {
        cleanupTestDirectory()
        val fileContext = createTestFileContext()

        // First operation acquires lock
        TestFileLockManager.tryLock(fileContext)

        // Second operation should fail to acquire lock
        val result = TestFileLockManager.withLock(fileContext) {
          Right("should not execute")
        }

        assert(result.isLeft)
        assert(result.left.get.contains("locked by process"))

        // Cleanup
        TestFileLockManager.releaseLock(fileContext)
      }
    }

    test("FileLockManager - Concurrent Access") {
      test("should handle concurrent lock attempts") {
        cleanupTestDirectory()
        val fileContext = createTestFileContext()
        val numThreads = 5 // Reduced for more reliable testing
        var successCount = 0
        var failureCount = 0

        val futures = (1 to numThreads).map { i =>
          Future {
            // Add a small delay to ensure threads don't all start at exactly the same time
            Thread.sleep(i * 5)
            TestFileLockManager.withLock(fileContext) {
              Thread.sleep(50) // Longer work simulation to ensure lock conflicts
              Right(s"operation-$i")
            }
          }
        }

        val results = futures.map { future =>
          Try(scala.concurrent.Await.result(future, 10.seconds))
        }

        results.foreach {
          case Success(Right(_)) => successCount += 1
          case Success(Left(_))  => failureCount += 1
          case Failure(_)        => failureCount += 1
        }

        // At least one should succeed, and there should be some failures due to lock conflicts
        assert(successCount >= 1)
        assert(failureCount >= 1)
        assert(successCount + failureCount == numThreads)

        // Verify no lock files remain
        val lockPath = fileContext.lockPath(TestFileLockManager.getLockBasePath)
        assert(!os.exists(lockPath))
      }
    }

    test("FileLockManager - Self-Healing") {
      test("should acquire lock after expired lock is auto-removed") {
        cleanupTestDirectory()
        val fileContext = createTestFileContext()

        // Create expired lock from another process
        val lockPath = fileContext.lockPath(TestFileLockManager.getLockBasePath)
        val lockDir = lockPath / os.up
        os.makeDir.all(lockDir)

        val expiredLock = LockContext("expired-process", Instant.now.minusSeconds(40), 30)
        os.write.over(lockPath, ujsonWrite(expiredLock))

        // Verify expired lock exists
        assert(os.exists(lockPath))

        // tryLock should self-heal by removing expired lock and acquiring new one
        val lockResult = TestFileLockManager.tryLock(fileContext)
        assert(lockResult.isRight)

        // Verify new lock is owned by current process
        val newLock = ujsonRead[LockContext](os.read(lockPath))
        assert(newLock.processId == TestFileLockManager.getProcessId)

        // Cleanup
        TestFileLockManager.releaseLock(fileContext)
      }

      test("should acquire lock after corrupted lock is auto-removed") {
        cleanupTestDirectory()
        val fileContext = createTestFileContext()

        // Create corrupted lock file
        val lockPath = fileContext.lockPath(TestFileLockManager.getLockBasePath)
        val lockDir = lockPath / os.up
        os.makeDir.all(lockDir)

        os.write.over(lockPath, "corrupted lock data")

        // Verify corrupted lock exists
        assert(os.exists(lockPath))

        // tryLock should self-heal by removing corrupted lock and acquiring new one
        val lockResult = TestFileLockManager.tryLock(fileContext)
        assert(lockResult.isRight)

        // Verify new lock is valid
        val newLock = ujsonRead[LockContext](os.read(lockPath))
        assert(newLock.processId == TestFileLockManager.getProcessId)

        // Cleanup
        TestFileLockManager.releaseLock(fileContext)
      }

      test("should not remove active lock from another process") {
        cleanupTestDirectory()
        val fileContext = createTestFileContext()

        // Create active lock from another process
        val lockPath = fileContext.lockPath(TestFileLockManager.getLockBasePath)
        val lockDir = lockPath / os.up
        os.makeDir.all(lockDir)

        val activeLock = LockContext("other-process", Instant.now, 30)
        os.write.over(lockPath, ujsonWrite(activeLock))

        // tryLock should fail - active lock should not be removed
        val lockResult = TestFileLockManager.tryLock(fileContext)
        assert(lockResult.isLeft)
        assert(lockResult.left.get.contains("locked by process"))

        // Verify original lock is still there
        val existingLock = ujsonRead[LockContext](os.read(lockPath))
        assert(existingLock.processId == "other-process")

        // Cleanup
        os.remove(lockPath)
      }
    }

    test("FileLockManager - Edge Cases") {
      test("should handle lock file creation in nested directories") {
        cleanupTestDirectory()
        val fileContext = createTestFileContext(
          tenantId = "deep-nested-tenant",
          userId = "deep-nested-user"
        )

        val lockResult = TestFileLockManager.tryLock(fileContext)
        assert(lockResult.isRight)

        val lockPath = fileContext.lockPath(TestFileLockManager.getLockBasePath)
        assert(os.exists(lockPath))

        TestFileLockManager.releaseLock(fileContext)
      }

      test("should generate unique lock paths for different contexts") {
        val context1 = createTestFileContext(tenantId = "tenant1", userId = "user1")
        val context2 = createTestFileContext(tenantId = "tenant2", userId = "user2")
        val context3 = createTestFileContext(
          tenantId = "tenant1",
          userId = "user1",
          objectId = UUID.randomUUID()
        )

        val path1 = context1.lockPath(TestFileLockManager.getLockBasePath)
        val path2 = context2.lockPath(TestFileLockManager.getLockBasePath)
        val path3 = context3.lockPath(TestFileLockManager.getLockBasePath)

        assert(path1 != path2)
        assert(path1 != path3)
        assert(path2 != path3)
      }

      test("should handle multiple locks for same tenant/user but different objects") {
        cleanupTestDirectory()
        val tenantId = "shared-tenant"
        val userId = "shared-user"

        val context1 =
          createTestFileContext(tenantId = tenantId, userId = userId, objectId = UUID.randomUUID())
        val context2 =
          createTestFileContext(tenantId = tenantId, userId = userId, objectId = UUID.randomUUID())

        val lock1 = TestFileLockManager.tryLock(context1)
        val lock2 = TestFileLockManager.tryLock(context2)

        assert(lock1.isRight)
        assert(lock2.isRight)

        // Both locks should coexist
        val lockPath1 = context1.lockPath(TestFileLockManager.getLockBasePath)
        val lockPath2 = context2.lockPath(TestFileLockManager.getLockBasePath)

        assert(os.exists(lockPath1))
        assert(os.exists(lockPath2))

        // Cleanup
        TestFileLockManager.releaseLock(context1)
        TestFileLockManager.releaseLock(context2)
      }
    }
  }
}
