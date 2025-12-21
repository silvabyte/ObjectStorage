package objectstorage.test.routes

import utest._
import objectstorage.config.Config
import objectstorage.test.TestServer.{stageFile, withServer, getObjectStorageApi, testApiKey}
import os._

object DownloadFileTest extends TestSuite {
  val envDirectory = sys.env.getOrElse("ENV_DIR", os.pwd.toString)
  Config.initialize(envDirectory)

  val tests = Tests {

    test("Download File") {
      withServer { host =>
        // Define tenant and user IDs.
        val tenantId = "1234"
        val userId = "5678"
        // The original file name.
        val fileName = "gain_audio_20250112_090406.wav"

        // Stage the file to the bucket:
        val objectId = stageFile(tenantId, userId, fileName).objectId
        // Now download the file using the new endpoint:
        // GET /api/v1/files/:objectId
        val destination = os.pwd / "bucket" / "downloadrecordingtest" / fileName

        getObjectStorageApi().downloadFile(
          objectId,
          destination.toString,
          Map("X-Tenant-ID" -> tenantId, "X-User-ID" -> userId, "x-api-key" -> testApiKey)
        ) match
          case Right(_) =>
            // Assert that the file was downloaded.
            assert(os.exists(os.Path(destination.toString)))
          case Left(e) => sys.error(e.getMessage)
      }
    }
  }
}
