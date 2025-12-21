package objectstorage.test.routes

import utest._
import objectstorage.config.Config
import objectstorage.test.TestServer.{stageFile, withServer, getObjectStorageApi, testApiKey}
import os._

object DeleteFileTest extends TestSuite {
  val envDirectory = sys.env.getOrElse("ENV_DIR", os.pwd.toString)
  Config.initialize(envDirectory)

  val tests = Tests {

    test("Delete File") {
      withServer { host =>
        val tenantId = "1234"
        val userId = "5678"
        val fileName = "gain_audio_20250112_090406.wav"
        val storedObject = stageFile(tenantId, userId, fileName)

        implicit val checked: Boolean = false
        getObjectStorageApi().deleteFile(
          storedObject.objectId,
          Map("X-Tenant-ID" -> tenantId, "X-User-ID" -> userId, "x-api-key" -> testApiKey)
        ) match {
          case Right(_) =>
            assert(
              !os.exists(os.pwd / "bucket" / tenantId / userId / storedObject.objectId.toString)
            )
            assert(!os.exists(
              os.pwd / "bucket" / tenantId / userId / s"${storedObject.objectId.toString}.json"
            ))
          case Left(e) => sys.error(e.getMessage)
        }
      }
    }
  }
}
