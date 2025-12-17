package objectstorage.config

import utest._

object ConfigTest extends TestSuite {
  val tests: Tests = Tests {
    test("DotEnv parses key-value pairs") {
      // Create a temp .env file
      val tempDir = os.temp.dir()
      val envFile = tempDir / ".env"
      os.write(
        envFile,
        """
          |# Comment line
          |KEY1=value1
          |KEY2="quoted value"
          |KEY3='single quoted'
          |EMPTY=
          |""".stripMargin
      )

      val dotenv = DotEnv.load(envFile.toString)

      assert(dotenv.get("KEY1") == Some("value1"))
      assert(dotenv.get("KEY2") == Some("quoted value"))
      assert(dotenv.get("KEY3") == Some("single quoted"))
      assert(dotenv.get("EMPTY") == Some(""))
      assert(dotenv.get("NONEXISTENT") == None)

      // Cleanup
      os.remove.all(tempDir)
    }

    test("Config provides defaults when not initialized") {
      // Config should provide sensible defaults
      assert(Config.OBJECT_STORAGE_PORT == 8080 || Config.isInitialized)
    }

    test("AppError captures message and cause") {
      val cause = new RuntimeException("root cause")
      val error = AppError("Something went wrong", Some(cause))

      assert(error.message == "Something went wrong")
      assert(error.cause == Some(cause))
      assert(error.getCause == cause)
    }

    test("AppError.fromThrowable creates error from exception") {
      val ex = new IllegalArgumentException("bad argument")
      val error = AppError.fromThrowable(ex)

      assert(error.message == "bad argument")
      assert(error.cause == Some(ex))
    }
  }
}
