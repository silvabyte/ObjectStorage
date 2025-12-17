package objectstorage.filemanager

import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.util.{Try, Success, Failure}
import objectstorage.logging.Log

/** Background process for cleaning up expired file locks.
  *
  * Runs periodically to remove stale locks that may have been left behind
  * by crashed processes.
  */
class LockCleanupProcess(
    private val intervalSeconds: Int = 60,
    private val autoStart: Boolean = true
) {
  private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
  private val running = new AtomicBoolean(false)
  private val processId = java.util.UUID.randomUUID().toString

  private val cleanupTask = new Runnable {
    override def run(): Unit = {
      val context = Map("processId" -> processId, "intervalSeconds" -> intervalSeconds)

      if (running.get()) {
        try {
          Log.info("Starting scheduled lock cleanup", context)
          FileLockManager.cleanupExpiredLocks()
          Log.info("Completed scheduled lock cleanup", context)
        } catch {
          case e: Exception =>
            Log.error("Lock cleanup process failed", context + ("error" -> e.getMessage))
        }
      }
    }
  }

  if (autoStart) {
    start()
  }

  def start(): Unit = {
    val context = Map("processId" -> processId, "intervalSeconds" -> intervalSeconds)

    if (running.compareAndSet(false, true)) {
      Log.info("Starting lock cleanup process", context)

      Try {
        executor.scheduleAtFixedRate(
          cleanupTask,
          intervalSeconds,
          intervalSeconds,
          TimeUnit.SECONDS
        )
      } match {
        case Success(_) =>
          Log.info("Lock cleanup process started successfully", context)
        case Failure(e) =>
          running.set(false)
          Log.error("Failed to start lock cleanup process", context + ("error" -> e.getMessage))
      }
    } else {
      Log.error("Lock cleanup process is already running", context)
    }
  }

  def stop(): Unit = {
    val context = Map("processId" -> processId)

    if (running.compareAndSet(true, false)) {
      Log.info("Stopping lock cleanup process", context)

      try {
        executor.shutdown()

        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
          Log.error("Lock cleanup process did not terminate gracefully, forcing shutdown", context)
          executor.shutdownNow()

          if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            Log.error("Lock cleanup process could not be terminated", context)
          }
        } else {
          Log.info("Lock cleanup process stopped successfully", context)
        }
      } catch {
        case e: Exception =>
          Log.error(
            "Error during lock cleanup process shutdown",
            context + ("error" -> e.getMessage)
          )
          executor.shutdownNow()
      }
    } else {
      Log.error("Lock cleanup process is not running", context)
    }
  }

  def isRunning: Boolean = running.get()

  def performImmediateCleanup(): Either[String, Unit] = {
    val context = Map("processId" -> processId)
    Log.info("Performing immediate lock cleanup", context)

    Try {
      FileLockManager.cleanupExpiredLocks()
    } match {
      case Success(_) =>
        Log.info("Immediate lock cleanup completed", context)
        Right(())
      case Failure(e) =>
        Log.error("Immediate lock cleanup failed", context + ("error" -> e.getMessage))
        Left(s"Immediate lock cleanup failed: ${e.getMessage}")
    }
  }
}

/** Singleton manager for the global lock cleanup process */
object LockCleanupProcess {
  private val instanceRef: AtomicReference[Option[LockCleanupProcess]] = new AtomicReference(None)

  def getInstance(intervalSeconds: Int = 60): LockCleanupProcess = {
    instanceRef.get() match {
      case Some(process) => process
      case None =>
        val process = new LockCleanupProcess(intervalSeconds, autoStart = false)
        if (instanceRef.compareAndSet(None, Some(process))) {
          process
        } else {
          instanceRef.get().get
        }
    }
  }

  def startGlobalCleanup(intervalSeconds: Int = 60): Unit = {
    val process = getInstance(intervalSeconds)
    if (!process.isRunning) {
      process.start()
    }
  }

  def stopGlobalCleanup(): Unit = {
    instanceRef.get().foreach { process =>
      process.stop()
      instanceRef.set(None)
    }
  }

  def isGlobalCleanupRunning: Boolean = {
    instanceRef.get().exists(_.isRunning)
  }
}
