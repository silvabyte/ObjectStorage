package objectstorage.test.filemanager

import utest._
import objectstorage.filemanager.{LockContext, FileContext}
import java.util.UUID
import java.time.Instant
import scala.util.{Try, Success, Failure}
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import os._
import upickle.default.{read => ujsonRead, write => ujsonWrite}

object FileLockManagerSimpleTest extends TestSuite {
  implicit val ec: ExecutionContext = ExecutionContext.global

  val tests = Tests {

    test("LockContext") {
      test("should create valid lock context") {
        val processId = "test-process-123"
        val now = Instant.now
        val lockContext = LockContext(processId, now)

        assert(lockContext.processId == processId)
        assert(lockContext.createdAt == now)
        assert(lockContext.timeoutMinutes == 5)
      }

      test("should check expiration correctly") {
        val processId = "test-process-123"
        val sixMinutesAgo = Instant.now.minusSeconds(360)
        val now = Instant.now

        val expiredLock = LockContext(processId, sixMinutesAgo, 5) // 5 minute timeout
        val activeLock = LockContext(processId, now, 5)            // 5 minute timeout

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
        assert(deserializedLock.timeoutMinutes == originalLock.timeoutMinutes)
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
      }
    }

    test("Lock File Operations") {
      test("should create and read lock files correctly") {
        val tempDir = os.temp.dir()
        val processId = "test-process-456"
        val lockContext = LockContext(processId, Instant.now)

        // Write lock file
        val lockFile = tempDir / "test.lock"
        val lockData = ujsonWrite(lockContext)
        os.write.over(lockFile, lockData)

        // Read lock file
        val readData = os.read(lockFile)
        val readLockContext = ujsonRead[LockContext](readData)

        assert(readLockContext.processId == lockContext.processId)
        assert(readLockContext.createdAt == lockContext.createdAt)
        assert(readLockContext.timeoutMinutes == lockContext.timeoutMinutes)

        // Cleanup
        os.remove.all(tempDir)
      }

      test("should handle atomic file operations") {
        val tempDir = os.temp.dir()
        val targetFile = tempDir / "target.lock"
        val tempFile = tempDir / "target.lock.tmp"

        val processId = "atomic-test-process"
        val lockContext = LockContext(processId, Instant.now)
        val lockData = ujsonWrite(lockContext)

        // Write to temp file first
        os.write.over(tempFile, lockData)
        assert(os.exists(tempFile))
        assert(!os.exists(targetFile))

        // Atomic move
        os.move(tempFile, targetFile)
        assert(!os.exists(tempFile))
        assert(os.exists(targetFile))

        // Verify content
        val readLockContext = ujsonRead[LockContext](os.read(targetFile))
        assert(readLockContext.processId == processId)

        // Cleanup
        os.remove.all(tempDir)
      }

      test("should handle corrupted lock file data") {
        val tempDir = os.temp.dir()
        val lockFile = tempDir / "corrupted.lock"

        // Write corrupted data
        os.write.over(lockFile, "invalid json data")

        // Should fail to parse
        val parseResult = Try(ujsonRead[LockContext](os.read(lockFile)))
        assert(parseResult.isFailure)

        // Cleanup
        os.remove.all(tempDir)
      }
    }

    test("Path Generation and Uniqueness") {
      test("should generate unique paths for different contexts") {
        val basePath = os.temp.dir()

        val context1 = FileContext("tenant1", "user1", UUID.randomUUID())
        val context2 = FileContext("tenant2", "user2", UUID.randomUUID())
        val context3 = FileContext("tenant1", "user1", UUID.randomUUID())

        val path1 = context1.lockPath(basePath)
        val path2 = context2.lockPath(basePath)
        val path3 = context3.lockPath(basePath)

        assert(path1 != path2)
        assert(path1 != path3)
        assert(path2 != path3)

        // Cleanup
        os.remove.all(basePath)
      }

      test("should handle nested directory structures") {
        val basePath = os.temp.dir()
        val fileContext = FileContext("deep-nested-tenant", "deep-nested-user", UUID.randomUUID())

        val lockPath = fileContext.lockPath(basePath)
        val lockDir = lockPath / os.up

        // Create the directory structure
        os.makeDir.all(lockDir)
        assert(os.exists(lockDir))

        // Should be able to create lock file
        val lockContext = LockContext("nested-test", Instant.now)
        os.write.over(lockPath, ujsonWrite(lockContext))
        assert(os.exists(lockPath))

        // Cleanup
        os.remove.all(basePath)
      }
    }

    test("Concurrent File Access Simulation") {
      test("should demonstrate file system level conflict detection") {
        val tempDir = os.temp.dir()
        val lockFile = tempDir / "shared.lock"

        val processId1 = "process-1"
        val processId2 = "process-2"

        val lock1 = LockContext(processId1, Instant.now)
        val lock2 = LockContext(processId2, Instant.now)

        // First process creates lock
        os.write.over(lockFile, ujsonWrite(lock1))
        assert(os.exists(lockFile))

        // Second process should detect existing lock
        val existingLock = ujsonRead[LockContext](os.read(lockFile))
        assert(existingLock.processId == processId1)
        assert(existingLock.processId != processId2)

        // Cleanup
        os.remove.all(tempDir)
      }

      test("should simulate lock expiration") {
        val tempDir = os.temp.dir()
        val lockFile = tempDir / "expiring.lock"

        // Create expired lock (created 10 minutes ago with 5 minute timeout)
        val expiredTime = Instant.now.minusSeconds(600)
        val expiredLock = LockContext("expired-process", expiredTime, 5)
        os.write.over(lockFile, ujsonWrite(expiredLock))

        // Read and check expiration
        val readLock = ujsonRead[LockContext](os.read(lockFile))
        assert(readLock.isExpired)

        // Simulate cleanup of expired lock
        if (readLock.isExpired) {
          os.remove(lockFile)
        }
        assert(!os.exists(lockFile))

        // Cleanup
        os.remove.all(tempDir)
      }
    }
  }
}
