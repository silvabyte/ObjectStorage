package objectstorage

import objectstorage.config.Config
import objectstorage.logging.Log
import objectstorage.routes.{FileRoutes, IndexRoutes}
import cask._
import java.util.concurrent.atomic.AtomicReference

/**
 * ObjectStorage application entry point.
 *
 * A standalone object storage service with multi-tenant file management.
 */
object ObjectStorageApp extends cask.Main {
  // Initialize configuration from environment
  private val envDirectory: String = sys.env.getOrElse("ENV_DIR", os.pwd.toString)
  Config.initialize(envDirectory)

  override def port: Int = Config.OBJECT_STORAGE_PORT
  override def host: String = Config.stripProtocol(Config.OBJECT_STORAGE_HOST)

  // No global decorators - auth is applied per-endpoint via @withAuth()

  Log.info("Starting ObjectStorageApp", Map("host" -> host, "port" -> port))

  // Define routes dynamically for easier testing
  private val routesRef: AtomicReference[Seq[cask.Routes]] =
    new AtomicReference(initializeRoutes())

  def allRoutes: Seq[cask.Routes] = routesRef.get()

  private def initializeRoutes(): Seq[cask.Routes] = Seq(
    IndexRoutes(),
    FileRoutes()
  )

  /** Override routes for testing or other purposes */
  def configureRoutes(routes: Seq[cask.Routes]): Unit = {
    routesRef.set(routes)
  }
}
