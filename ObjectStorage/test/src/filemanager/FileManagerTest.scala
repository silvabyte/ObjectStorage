package objectstorage.test.filemanager

import utest._
import objectstorage.filemanager.FileManager
import objectstorage.test.TestServer.copyResourceFileToTemp
import objectstorage.config.Config
import java.nio.charset.StandardCharsets

object FileManagerTest extends TestSuite {
  val envDirectory = sys.env.getOrElse("ENV_DIR", os.pwd.toString)
  Config.initialize(envDirectory)

  def createTempFileWithContent(content: String): os.Path = {
    val temp = os.temp(content.getBytes(StandardCharsets.UTF_8))
    temp
  }

  def createTempFileWithSize(sizeInBytes: Long): os.Path = {
    val temp = os.temp()
    val randomData =
      Array.fill[Byte](sizeInBytes.toInt)((scala.util.Random.nextInt(256) - 128).toByte)
    os.write.over(temp, randomData)
    temp
  }

  val tests = Tests {
    test("computeChecksum") {
      test("with audio file") {
        val temp = copyResourceFileToTemp("gain_audio_20250112_090406.wav")
        val checksumStream = FileManager.computeChecksum(temp)
        val checksumFull = FileManager.computeChecksum(temp)
        assert(checksumStream == checksumFull)
      }

      test("with empty file") {
        val temp = createTempFileWithContent("")
        val checksumStream = FileManager.computeChecksum(temp)
        val checksumFull = FileManager.computeChecksum(temp)
        assert(checksumStream == checksumFull)
        // SHA-256 of empty string
        assert(checksumStream == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
      }

      test("with small text file") {
        val content = "Hello, World!"
        val temp = createTempFileWithContent(content)
        val checksumStream = FileManager.computeChecksum(temp)
        val checksumFull = FileManager.computeChecksum(temp)
        assert(checksumStream == checksumFull)
      }

      test("with different buffer sizes") {
        val temp = createTempFileWithSize(1024 * 1024) // 1MB file
        val checksum1 = FileManager.computeChecksum(temp, 1024)  // 1KB buffer
        val checksum2 = FileManager.computeChecksum(temp, 8192)  // 8KB buffer
        val checksum3 = FileManager.computeChecksum(temp, 32768) // 32KB buffer
        assert(checksum1 == checksum2)
        assert(checksum2 == checksum3)
      }

      test("with large file") {
        val temp = createTempFileWithSize(10 * 1024 * 1024) // 10MB file
        val checksumStream = FileManager.computeChecksum(temp)
        val checksumFull = FileManager.computeChecksum(temp)
        assert(checksumStream == checksumFull)
      }
    }
  }
}
