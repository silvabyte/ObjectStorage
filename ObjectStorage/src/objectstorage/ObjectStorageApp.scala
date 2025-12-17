package objectstorage

import objectstorage.config.Config
import objectstorage.logging.Log

/** ObjectStorage application entry point.
  *
  * A standalone object storage service with multi-tenant file management.
  */
object ObjectStorageApp extends cask.Main {
  // Initialize configuration from environment
  private val envDirectory: String = sys.env.getOrElse("ENV_DIR", os.pwd.toString)
  Config.initialize(envDirectory)

  override def port: Int = Config.OBJECT_STORAGE_PORT
  override def host: String = Config.stripProtocol(Config.OBJECT_STORAGE_HOST)

  Log.info("Starting ObjectStorageApp", Map("host" -> host, "port" -> port))

  // Routes will be added in task .3
  val allRoutes: Seq[cask.Routes] = Seq.empty
}
