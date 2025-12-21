package objectstorage.test

import java.util.UUID
import java.time.Instant
import java.net.{ServerSocket, InetSocketAddress}
import java.util.concurrent.atomic.AtomicReference
import os._
import io.undertow.Undertow
import objectstorage.config.Config
import objectstorage.ObjectStorageApp
import objectstorage.models.StoredObject
import objectstorage.client.ObjectStorageClient
import objectstorage.routes.{FileRoutes, IndexRoutes}
import objectstorage.filemanager.FileManager

/**
 * Test server harness for integration tests.
 *
 * Provides utilities for starting/stopping the ObjectStorage server and staging test files.
 */
object TestServer {

  val routes = Seq(new IndexRoutes(), new FileRoutes())

  // disable requests throwing an error for non 200 responses
  implicit val checked: Boolean = false

  private val server = new AtomicReference[Option[Undertow]](None)
  private val currentPort = new AtomicReference[Option[Int]](None)
  private val objectStorageClient = new AtomicReference[Option[ObjectStorageClient]](None)

  private def findAvailablePort(): Int = {
    val socket = new ServerSocket()
    try {
      socket.bind(new InetSocketAddress(0))
      socket.getLocalPort
    } finally {
      socket.close()
    }
  }

  def startServer(): String = this.synchronized {
    if (server.get().isEmpty) {
      val port = findAvailablePort()
      currentPort.set(Some(port))
      ObjectStorageApp.configureRoutes(routes)
      val newServer = Undertow.builder
        .addHttpListener(port, "0.0.0.0")
        .setHandler(ObjectStorageApp.defaultHandler)
        .build
      newServer.start()
      server.set(Some(newServer))
      val url = s"http://0.0.0.0:$port"
      objectStorageClient.set(Some(ObjectStorageClient(url)))
      Thread.sleep(500) // Give Undertow time to bind to the port
      url
    } else {
      s"http://0.0.0.0:${currentPort.get().get}"
    }
  }

  def getObjectStorageApi(): ObjectStorageClient = {
    startServer() // Ensure server is started
    objectStorageClient.get().getOrElse(sys.error("Server not started"))
  }

  def stopServer(): Unit = this.synchronized {
    server.get().foreach(_.stop())
    server.set(None)
    currentPort.set(None)
    objectStorageClient.set(None)
  }

  def withServer[T](f: String => T): T = {
    val host = startServer()
    try f(host)
    finally stopServer()
  }

  def copyResourceFileToTemp(fileName: String): Path = {
    val resourceFilePath = os.resource / "test" / fileName
    val temp = os.temp()
    os.write.over(temp, os.read.stream(resourceFilePath), createFolders = true)
    temp
  }

  // Default test API key (matches Config default)
  val testApiKey = "test-api-key"

  def getStagedFileByChecksum(
      tenantId: String,
      userId: String,
      fileName: String
  ): Option[StoredObject] = {
    val host = startServer()
    val temp = copyResourceFileToTemp(fileName)
    val checksum = FileManager.computeChecksum(temp)
    objectStorageClient.get().get.checkFileByChecksum(
      checksum,
      Map("X-Tenant-Id" -> tenantId, "X-User-Id" -> userId, "x-api-key" -> testApiKey)
    ) match {
      case Right(Some(so)) => Some(so)
      case _ => None
    }
  }

  def stageFile(
      tenantId: String = "1234",
      userId: String = "5678",
      fileName: String = "gain_audio_20250112_090406.wav"
  ): StoredObject = {
    val host = startServer()
    val temp = copyResourceFileToTemp(fileName)
    val checksum = FileManager.computeChecksum(temp)

    implicit val checked: Boolean = false
    objectStorageClient.get().get.checkFileByChecksum(
      checksum,
      Map("X-Tenant-Id" -> tenantId, "X-User-Id" -> userId, "x-api-key" -> testApiKey)
    ) match {
      case Right(Some(so)) => so
      case _ =>
        val file = os.read.stream(temp)
        objectStorageClient.get().get.uploadFile(
          file,
          Map(
            "X-Tenant-Id" -> tenantId,
            "X-User-Id" -> userId,
            "x-file-name" -> fileName,
            "x-mimetype" -> "audio/wav",
            "x-api-key" -> testApiKey
          )
        ) match {
          case Right(so) => so
          case Left(e) => sys.error(s"Failed to upload file: $e")
        }
    }
  }

  def tryTeardownFile(tenantId: String, userId: String, fileName: String): Unit = {
    val host = startServer()
    getStagedFileByChecksum(tenantId, userId, fileName) match
      case None =>
      case Some(so) =>
        requests.delete(
          s"$host/api/v1/files/${so.objectId}",
          headers = Map("X-Tenant-Id" -> tenantId, "X-User-Id" -> userId, "x-api-key" -> testApiKey)
        )
  }
}
