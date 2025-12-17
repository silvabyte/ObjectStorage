package objectstorage.config

import scala.util.{Try, Success, Failure}
import java.util.concurrent.atomic.AtomicReference

/** Configuration manager for ObjectStorage service.
  *
  * Loads configuration from .env files and provides typed accessors.
  */
object Config {
  private val dotenvRef: AtomicReference[Option[DotEnv]] = new AtomicReference(None)

  /** Initialize the configuration with a specified directory.
    *
    * @param envDirectory
    *   Directory containing the .env file
    */
  def initialize(envDirectory: String): Unit = {
    dotenvRef.set(Some(DotEnv.load(s"$envDirectory/.env")))
  }

  /** Check if config has been initialized */
  def isInitialized: Boolean = dotenvRef.get().isDefined

  // Helper method to get config value or return Either
  private def getConfigValue(key: String): Either[AppError, String] = {
    dotenvRef.get() match {
      case None => Left(AppError("Config not initialized"))
      case Some(env) =>
        env.get(key) match {
          case Some(value) => Right(value)
          case None        => Left(AppError(s"$key not set"))
        }
    }
  }

  private def getConfigInt(key: String): Either[AppError, Int] = {
    getConfigValue(key).flatMap { value =>
      Try(value.toInt) match {
        case Success(intValue) => Right(intValue)
        case Failure(_)        => Left(AppError(s"$key is not a valid integer: $value"))
      }
    }
  }

  // ObjectStorage-specific configuration
  lazy val OBJECT_STORAGE_HOST: String = getConfigValue("OBJECT_STORAGE_HOST") match {
    case Right(value) => value
    case Left(_)      => "0.0.0.0"
  }

  lazy val OBJECT_STORAGE_PORT: Int = getConfigInt("OBJECT_STORAGE_PORT") match {
    case Right(value) => value
    case Left(_)      => 8080
  }

  lazy val OBJECT_STORAGE_URL: String = toUrl(OBJECT_STORAGE_HOST, OBJECT_STORAGE_PORT)

  // Redis/KeyDB configuration for distributed locking
  lazy val KEYDB_HOST: String = getConfigValue("KEYDB_HOST") match {
    case Right(value) => value
    case Left(_)      => "localhost"
  }

  lazy val KEYDB_PORT: Int = getConfigInt("KEYDB_PORT") match {
    case Right(value) => value
    case Left(_)      => 6379
  }

  // Logging configuration
  lazy val LOG_PRETTY: Boolean = dotenvRef.get() match {
    case None      => false
    case Some(env) => env.getOrElse("LOG_PRETTY", "false").toBoolean
  }

  // Storage configuration
  lazy val STORAGE_BASE_PATH: String = getConfigValue("STORAGE_BASE_PATH") match {
    case Right(value) => value
    case Left(_)      => "./bucket"
  }

  private def toUrl(host: String, port: Int): String = {
    if (host.startsWith("http")) {
      host + ":" + port.toString
    } else {
      s"http://${host}:${port.toString}"
    }
  }

  def stripProtocol(url: String): String = {
    url.replaceFirst("^https?://", "")
  }

  /** Generic get method for any config value */
  def get(key: String, default: String = ""): String = {
    dotenvRef.get().flatMap(_.get(key)).getOrElse(default)
  }

  /** Get integer config value */
  def getInt(key: String, default: Int = 0): Int = {
    dotenvRef.get().flatMap(_.get(key)).map(_.toIntOption.getOrElse(default)).getOrElse(default)
  }

  /** Get long config value */
  def getLong(key: String, default: Long = 0L): Long = {
    dotenvRef.get().flatMap(_.get(key)).map(_.toLongOption.getOrElse(default)).getOrElse(default)
  }
}
