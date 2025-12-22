package objectstorage.config

import scala.collection.mutable

/** Simple .env file loader for configuration with sys.env fallback */
case class DotEnv(env: mutable.Map[String, String]) {
  /** Get a value from the .env file, falling back to system environment */
  def get(key: String): Option[String] = env.get(key).orElse(sys.env.get(key))

  /** Get a value with default, checking .env first, then sys.env */
  def getOrElse(key: String, default: String): String =
    env.get(key).orElse(sys.env.get(key)).getOrElse(default)

  def set(key: String, value: String): Unit = env(key) = value
}

object DotEnv {

  /**
   * Load environment variables from a .env file.
   *
   * @param filePath
   *   Path to the .env file. Defaults to ".env" in the current directory.
   * @param overrideExisting
   *   If true, existing environment variables will be overridden.
   * @return
   *   A DotEnv instance with all loaded key-value pairs.
   */
  def load(filePath: String = ".env", overrideExisting: Boolean = true): DotEnv = {
    val lines: Seq[String] = scala.util.Try(os.read.lines(os.Path(filePath))).getOrElse(Seq.empty)
    val env = lines.map(parseLine).foldLeft(Map[String, String]()) {
      case (acc, Some((key, value))) =>
        if (overrideExisting || sys.env.get(key).isEmpty) {
          acc + (key -> value)
        } else {
          acc
        }
      case (acc, None) => acc
    }
    DotEnv(env.to(mutable.Map))
  }

  /** Parse a line from the .env file into a key-value pair. */
  private def parseLine(line: String): Option[(String, String)] = {
    val trimmed = line.trim
    if (trimmed.isEmpty || trimmed.startsWith("#")) {
      None
    } else {
      val parts = trimmed.split("=", 2).map(_.trim)
      if (parts.length == 2) {
        Some(parts(0) -> unquote(parts(1)))
      } else {
        None
      }
    }
  }

  /** Remove surrounding quotes from a value, if present. */
  private def unquote(value: String): String = {
    if (
      (value.startsWith("\"") && value.endsWith("\"")) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value.substring(1, value.length - 1)
    } else {
      value
    }
  }
}
