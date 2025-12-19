package objectstorage.routes

import cask.model.Response
import boogieloops.web._
import boogieloops.web.Web.ValidatedRequestReader
import boogieloops.web.openapi.config.OpenAPIConfig
import boogieloops.schema.derivation.Schematic
import objectstorage.models.{StatusResponse, PingResponse}
import upickle.default.write

/**
 * Health and status endpoints for the ObjectStorage service.
 *
 * Provides basic health checks, ping endpoints, and OpenAPI spec generation.
 */
case class IndexRoutes() extends cask.Routes {
  def apiPrefix = "/api/v1"

  /** Root endpoint - basic status check */
  @Web.get(
    "/",
    RouteSchema(
      summary = Some("Root status check"),
      description = Some("Basic status check endpoint"),
      tags = List("Health"),
      responses = Map(
        200 -> ApiResponse("Service is running", Schematic[StatusResponse])
      )
    )
  )
  def index(r: ValidatedRequest): Response[String] = {
    Response(
      write(StatusResponse("ok")),
      200,
      Seq("Content-Type" -> "application/json")
    )
  }

  /** Ping endpoint for basic connectivity check */
  @Web.get(
    s"$apiPrefix/ping",
    RouteSchema(
      summary = Some("Ping"),
      description = Some("Simple ping endpoint for connectivity check"),
      tags = List("Health"),
      responses = Map(
        200 -> ApiResponse("Pong", Schematic[PingResponse])
      )
    )
  )
  def ping(r: ValidatedRequest): Response[String] = {
    Response(
      write(PingResponse()),
      200,
      Seq("Content-Type" -> "application/json")
    )
  }

  /** Health check endpoint for monitoring systems */
  @Web.get(
    s"$apiPrefix/health",
    RouteSchema(
      summary = Some("Health check"),
      description = Some("Health check endpoint for monitoring systems"),
      tags = List("Health"),
      responses = Map(
        200 -> ApiResponse("Service is healthy", Schematic[StatusResponse])
      )
    )
  )
  def health(r: ValidatedRequest): Response[String] = {
    Response(
      write(StatusResponse("ok")),
      200,
      Seq("Content-Type" -> "application/json")
    )
  }

  /** OpenAPI specification endpoint */
  @Web.swagger(
    "/openapi",
    OpenAPIConfig(
      title = "ObjectStorage API",
      summary = Some("REST API for file storage and management"),
      description =
        "Multi-tenant file storage with metadata management, checksums, and deduplication.",
      version = "1.0.0"
    )
  )
  def openapi(): String = ""

  initialize()
}
