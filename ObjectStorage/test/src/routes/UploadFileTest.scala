package objectstorage.test.routes

import utest._
import objectstorage.config.{Config, AppError}
import objectstorage.test.TestServer.{withServer, tryTeardownFile, getObjectStorageApi}
import os._

object UploadFileTest extends TestSuite {
  val envDirectory = sys.env.getOrElse("ENV_DIR", os.pwd.toString)
  Config.initialize(envDirectory)

  val tenantId = "1234"
  val userId = "5678"
  val fileName = "gain_audio_20250112_090406.wav"

  val tests = Tests {

    test("Teardown files") {
      withServer { host => tryTeardownFile(tenantId, userId, fileName) }
    }
    test("Upload file successfully") {
      withServer { host =>
        val file = os.read.stream(os.resource / "test" / fileName)
        getObjectStorageApi()
          .uploadFile(
            file,
            Map(
              "X-Tenant-Id" -> tenantId,
              "X-User-Id" -> userId,
              "x-file-name" -> fileName,
              "x-mimetype" -> "audio/wav"
            )
          ) match {
          case Left(e) =>
            sys.error(s"Failed to upload file: ${e.getMessage}")
          case Right(obj) =>
            assert(obj.fileName == fileName)
            assert(obj.objectId.toString.nonEmpty)
            assert(obj.bucket.nonEmpty)
            assert(obj.mimeType == Some("audio/wav"))
        }
      }
      test("Uploading a duplicate file should return 304 status code and the stored object") {
        withServer { host =>
          implicit val checked = false
          val tenantId = "1234"
          val userId = "5678"
          val fileName = "gain_audio_20250112_090406.wav"
          val file = os.read.stream(os.resource / "test" / fileName)
          getObjectStorageApi().uploadFile(
            file,
            Map(
              "X-Tenant-Id" -> tenantId,
              "X-User-Id" -> userId,
              "x-file-name" -> fileName,
              "x-mimetype" -> "audio/wav"
            )
          ) match {
            case Left(e) =>
              sys.error(s"Expected upload file to succeed: ${e.getMessage}")
            case Right(obj) =>
              assert(obj.fileName == fileName)
              assert(obj.objectId.toString.nonEmpty)
              assert(obj.bucket.nonEmpty)
              assert(obj.mimeType == Some("audio/wav"))
            case _ =>
              sys.error("Unexpected match case in upload file test")
          }
        }
      }
    }
  }
}
