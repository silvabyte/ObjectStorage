package objectstorage.test.routes

import utest._
import objectstorage.config.Config
import objectstorage.models.StoredObject
import objectstorage.test.TestServer.{stageFile, withServer, getObjectStorageApi, testApiKey}
import os._

object ListFilesTest extends TestSuite {
  val envDirectory = sys.env.getOrElse("ENV_DIR", os.pwd.toString)
  Config.initialize(envDirectory)

  val tests = Tests {

    test("List Files") {
      withServer { host =>
        val tenantId = "1234"
        val userId = "5678"
        val fileName = "gain_audio_20250112_090406.wav"
        val storedObject = stageFile(tenantId, userId, fileName).objectId
        val objs = getObjectStorageApi()
          .listFiles(Map("X-Tenant-ID" -> tenantId, "X-User-ID" -> userId, "x-api-key" -> testApiKey))
          .fold(e => sys.error(e.getMessage), identity)
        assert(objs.length > 0)
        assert(objs.map(_.objectId).contains(storedObject))
      }
    }
  }
}
