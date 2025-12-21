package objectstorage.decorators

import cask.model.Response.Raw
import cask.model.{Request, Response}
import cask.router.Result
import io.undertow.util.AttachmentKey
import objectstorage.config.Config
import objectstorage.logging.Log
import upickle.default._

/**
 * Cask middleware decorators for request/response handling.
 */
object Decorators {

  /**
   * Represents an authenticated user's identity.
   *
   * @param tenantId The tenant identifier for multi-tenant isolation
   * @param userId The unique identifier of the authenticated user
   * @param claims Optional additional claims
   */
  case class AuthenticatedUser(
      tenantId: String,
      userId: String,
      claims: Map[String, String] = Map.empty
  )

  /** AttachmentKey for storing AuthenticatedUser in the Undertow exchange */
  val AuthenticatedUserKey: AttachmentKey[AuthenticatedUser] =
    AttachmentKey.create(classOf[AuthenticatedUser])

  /**
   * Extract the AuthenticatedUser from a cask Request.
   *
   * @param request The incoming request
   * @return Some(user) if authenticated, None otherwise
   */
  def getAuthenticatedUser(request: cask.Request): Option[AuthenticatedUser] = {
    Option(request.exchange.getAttachment(AuthenticatedUserKey))
  }

  /**
   * Authentication decorator that validates requests using API Key.
   *
   * This decorator:
   * 1. Checks if the path is whitelisted (public).
   * 2. Checks `x-api-key` header against Config.API_KEY.
   * 3. Extracts `x-tenant-id` and `x-user-id` headers to form identity.
   * 4. Stores identity in exchange attachment.
   */
  class withAuth extends cask.RawDecorator {

    /** Case class for JSON error responses */
    private case class AuthError(message: String, status: String) derives ReadWriter

    // Public paths that bypass authentication
    private val publicPaths = Set("/", "/api/v1/ping", "/api/v1/health", "/openapi")

    def wrapFunction(request: cask.Request, delegate: Delegate): Result[Raw] = {
      val path = request.exchange.getRequestPath

      // 1. Bypass auth for public paths
      if (publicPaths.contains(path) || path.startsWith("/openapi")) {
        return delegate(request, Map.empty)
      }

      // 2. Validate API Key
      val apiKeyHeader = Option(request.exchange.getRequestHeaders.getFirst("x-api-key"))

      if (!apiKeyHeader.contains(Config.API_KEY)) {
        Log.warn(
          s"Authentication failed: Invalid or missing API Key",
          Map("url.path" -> path, "client.ip" -> request.exchange.getSourceAddress.getAddress.getAddress.toString)
        )
        return Result.Success(
          Response(
            write(AuthError("Invalid API Key", "unauthorized")),
            401,
            Seq("Content-Type" -> "application/json")
          )
        )
      }

      // 3. Extract Identity
      val tenantId = Option(request.exchange.getRequestHeaders.getFirst("x-tenant-id"))
      val userId = Option(request.exchange.getRequestHeaders.getFirst("x-user-id"))

      (tenantId, userId) match {
        case (Some(tid), Some(uid)) =>
          val user = AuthenticatedUser(tid, uid)
          request.exchange.putAttachment(AuthenticatedUserKey, user)
          delegate(request, Map.empty)

        case _ =>
          Log.warn(
            s"Authentication failed: Missing identity headers",
            Map("url.path" -> path)
          )
          Result.Success(
            Response(
              write(AuthError("Missing required headers: x-tenant-id, x-user-id", "unauthorized")),
              401, // Or 400, but using 401 for auth failure consistency
              Seq("Content-Type" -> "application/json")
            )
          )
      }
    }
  }

  /**
   * Logs request and response details including timing, status, and errors.
   */
  class withResponseLog extends cask.RawDecorator {

    def wrapFunction(request: cask.Request, delegate: Delegate): Result[Raw] = {
      val startTime = System.nanoTime()
      val result = delegate(request, Map.empty)
      val responseTimeNanos = System.nanoTime() - startTime
      val responseTimeMillis = responseTimeNanos / 1000000.0

      val baseMeta = Map(
        "url.path" -> request.exchange.getRequestPath,
        "url.query" -> request.exchange.getQueryString,
        "url.full" -> request.exchange.getRequestURL,
        "client.ip" -> request.exchange.getSourceAddress.getAddress.getAddress.toString,
        "client.port" -> request.exchange.getSourceAddress.getPort,
        "user_agent.original" -> request.exchange.getRequestHeaders.getFirst("user-agent"),
        "request.method" -> request.exchange.getRequestMethod,
        "request.id" -> request.exchange.getRequestId,
        "request-id" -> request.exchange.getRequestHeaders.getFirst("request-id"),
        "x-tenant-id" -> request.exchange.getRequestHeaders.getFirst("x-tenant-id"),
        "x-user-id" -> request.exchange.getRequestHeaders.getFirst("x-user-id"),
        "request.body.bytes" -> Option(
          request.exchange.getRequestHeaders.getFirst("content-length")
        ).flatMap(_.toIntOption).getOrElse(0),
        "response.time.ms" -> responseTimeMillis
      )

      result match {
        case Result.Success(response) =>
          val meta = baseMeta + (
            "response.status_code" -> response.statusCode,
            "response.body.bytes" -> response.data.headers
              .find(_._1.toLowerCase() == "content-length")
              .flatMap(_._2.toIntOption)
              .getOrElse(0)
          )
          if (response.statusCode != 200 && response.statusCode != 201) {
            Log.error(s"${response.data.toString()}")
          }

          Log.info(
            s"Path: ${meta.get("url.path").getOrElse("")} | Method: ${meta.get("request.method").getOrElse("")} | Status: ${response.statusCode} | Time: ${responseTimeMillis}ms",
            meta
          )

        case Result.Error.Exception(throwable: Throwable) =>
          val meta = baseMeta + ("exception" -> throwable.getMessage)
          Log.error(
            s"Path: ${meta.get("url.path").getOrElse("")} | Method: ${meta.get("request.method").getOrElse("")} | Exception: ${throwable.getMessage} | Time: ${responseTimeMillis}ms",
            meta
          )
        case invalidArgsError: Result.Error.InvalidArguments =>
          val meta = baseMeta + ("invalid_arguments" -> invalidArgsError)
          Log.error(
            s"Path: ${meta.get("url.path").getOrElse("")} | Method: ${meta.get("request.method")} | Invalid Arguments: ${invalidArgsError} | Time: ${responseTimeMillis}ms",
            meta
          )
        case mismatchedArgsError: Result.Error.MismatchedArguments =>
          val meta = baseMeta + ("mismatched_arguments" -> mismatchedArgsError)
          Log.error(
            s"Path: ${meta.get("url.path").getOrElse("")} | Method: ${meta.get("request.method")} | Mismatched Arguments: ${mismatchedArgsError} | Time: ${responseTimeMillis}ms",
            meta
          )
      }

      result
    }
  }
}
