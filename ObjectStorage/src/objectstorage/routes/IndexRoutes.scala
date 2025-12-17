package objectstorage.routes

import cask.model.Response

/** Health and status endpoints for the ObjectStorage service.
  *
  * Provides basic health checks and ping endpoints for monitoring.
  */
case class IndexRoutes() extends cask.Routes {
  def apiPrefix = "/api/v1"

  /** Root endpoint - basic status check */
  @cask.get("/")
  def index(): Response[ujson.Obj] = {
    Response(ujson.Obj("status" -> "ok"))
  }

  /** Ping endpoint for basic connectivity check */
  @cask.get(s"$apiPrefix/ping")
  def ping(): Response[ujson.Obj] = {
    Response(ujson.Obj("message" -> "pong"))
  }

  /** Health check endpoint for monitoring systems */
  @cask.get(s"$apiPrefix/health")
  def health(): Response[ujson.Obj] = {
    Response(ujson.Obj("status" -> "ok"))
  }

  initialize()
}
