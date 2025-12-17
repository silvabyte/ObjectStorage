package objectstorage.test.routes

import utest._
import objectstorage.config.{Config, AppError}
import objectstorage.test.TestServer.{stageFile, withServer, getObjectStorageApi}
import objectstorage.models.StoredObject
import java.util.UUID
import os._

object GetFileMetaDataTest extends TestSuite {
  val envDirectory = sys.env.getOrElse("ENV_DIR", os.pwd.toString)
  Config.initialize(envDirectory)

  val tests = Tests {

    test("Get file meta data successfully") {
      withServer { host =>
        // Define tenant and user IDs.
        val tenantId = "1234"
        val userId = "5678"
        // The original file name.
        val fileName = "gain_audio_20250112_090406.wav"

        val storedObject = stageFile(tenantId, userId, fileName)

        // Now get the file meta data:
        // GET /api/v1/files/metadata/:objectId
        val obj = {
          getObjectStorageApi()
            .getFileMetadata(
              storedObject.objectId,
              Map("X-Tenant-ID" -> tenantId, "X-User-ID" -> userId)
            )
            .getOrElse(throw new Exception("Failed to get file metadata"))
        }
        // Assert that the response is the expected stored object.
        assert(obj == storedObject)
      }
    }
    test("Get File Meta Data with invalid objectId") {
      withServer { host =>
        val tenantId = "1234"
        val userId = "5678"
        implicit val checked: Boolean = false
        getObjectStorageApi()
          .getFileMetadata(
            UUID.randomUUID(),
            Map("X-Tenant-ID" -> tenantId, "X-User-ID" -> userId)
          ) match {
          case Left(e) =>
            assert(e == AppError("Failed to get file metadata: 404"))
          case Right(_) =>
            throw new Exception("Expected get file metadata to fail")
        }
      }
    }
  }

}
