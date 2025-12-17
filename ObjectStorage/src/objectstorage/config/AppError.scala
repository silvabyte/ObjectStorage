package objectstorage.config

import objectstorage.logging.Log

/**
 * Application error type for ObjectStorage.
 *
 * Provides a consistent error type with optional cause tracking.
 */
case class AppError(message: String, cause: Option[Throwable] = None) extends Exception(message, cause.orNull) {

  /** Log this error using the Log utility */
  def log(): Unit = {
    Log.error(message)
    cause.foreach(c => Log.error(c.getMessage))
  }
}

object AppError {

  /** Create an AppError from a throwable */
  def fromThrowable(t: Throwable): AppError = {
    AppError(t.getMessage, Some(t))
  }

  /** Create an AppError with just a message */
  def apply(message: String): AppError = new AppError(message, None)
}
