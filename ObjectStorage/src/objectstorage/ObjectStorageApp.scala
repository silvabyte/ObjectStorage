package objectstorage

// Placeholder - will be populated in task .3
object ObjectStorageApp extends cask.Main {
  override def port: Int = 8080
  override def host: String = "0.0.0.0"

  val allRoutes: Seq[cask.Routes] = Seq.empty
}
