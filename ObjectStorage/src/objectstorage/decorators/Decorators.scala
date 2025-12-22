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
 *
 * Decorators are composable and applied per-endpoint:
 * - requireApiKey: Validates x-api-key header
 * - withIdentity: Extracts x-tenant-id and x-user-id headers
 * - withAuth: Convenience that combines requireApiKey + withIdentity
 * - withRequestLog: Logs method, path, status, duration
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

  // ============================================================================
  // requireApiKey - Validates API key header
  // ============================================================================

  /**
   * Validates the x-api-key header against the configured API key.
   *
   * Returns 401 if the key is missing or invalid.
   */
  class requireApiKey extends cask.RawDecorator {

    private case class ApiKeyError(message: String, status: String) derives ReadWriter

    def wrapFunction(request: cask.Request, delegate: Delegate): Result[Raw] = {
      val apiKeyHeader = Option(request.exchange.getRequestHeaders.getFirst("x-api-key"))

      if (!apiKeyHeader.contains(Config.API_KEY)) {
        Log.warn(
          "Authentication failed: Invalid or missing API Key",
          Map(
            "url.path" -> request.exchange.getRequestPath,
            "client.ip" -> request.exchange.getSourceAddress.getAddress.getHostAddress
          )
        )
        Result.Success(
          Response(
            write(ApiKeyError("Invalid API Key", "unauthorized")),
            401,
            Seq("Content-Type" -> "application/json")
          )
        )
      } else {
        delegate(request, Map.empty)
      }
    }
  }

  // ============================================================================
  // withIdentity - Extracts tenant/user identity from headers
  // ============================================================================

  /**
   * Extracts x-tenant-id and x-user-id headers and stores them as AuthenticatedUser.
   *
   * Returns 401 if required headers are missing.
   */
  class withIdentity extends cask.RawDecorator {

    private case class IdentityError(message: String, status: String) derives ReadWriter

    def wrapFunction(request: cask.Request, delegate: Delegate): Result[Raw] = {
      val tenantId = Option(request.exchange.getRequestHeaders.getFirst("x-tenant-id"))
      val userId = Option(request.exchange.getRequestHeaders.getFirst("x-user-id"))

      (tenantId, userId) match {
        case (Some(tid), Some(uid)) =>
          val user = AuthenticatedUser(tid, uid)
          request.exchange.putAttachment(AuthenticatedUserKey, user)
          delegate(request, Map.empty)

        case _ =>
          Log.warn(
            "Authentication failed: Missing identity headers",
            Map("url.path" -> request.exchange.getRequestPath)
          )
          Result.Success(
            Response(
              write(IdentityError("Missing required headers: x-tenant-id, x-user-id", "unauthorized")),
              401,
              Seq("Content-Type" -> "application/json")
            )
          )
      }
    }
  }

  // ============================================================================
  // withAuth - Convenience decorator combining requireApiKey + withIdentity
  // ============================================================================

  /**
   * Convenience decorator that validates API key AND extracts identity.
   *
   * Equivalent to @requireApiKey() @withIdentity() but in a single annotation.
   */
  class withAuth extends cask.RawDecorator {

    private val apiKeyDecorator = new requireApiKey()
    private val identityDecorator = new withIdentity()

    private case class AuthError(message: String, status: String) derives ReadWriter

    def wrapFunction(request: cask.Request, delegate: Delegate): Result[Raw] = {
      // First validate API key
      val apiKeyHeader = Option(request.exchange.getRequestHeaders.getFirst("x-api-key"))

      if (!apiKeyHeader.contains(Config.API_KEY)) {
        Log.warn(
          "Authentication failed: Invalid or missing API Key",
          Map(
            "url.path" -> request.exchange.getRequestPath,
            "client.ip" -> request.exchange.getSourceAddress.getAddress.getHostAddress
          )
        )
        return Result.Success(
          Response(
            write(AuthError("Invalid API Key", "unauthorized")),
            401,
            Seq("Content-Type" -> "application/json")
          )
        )
      }

      // Then extract identity
      val tenantId = Option(request.exchange.getRequestHeaders.getFirst("x-tenant-id"))
      val userId = Option(request.exchange.getRequestHeaders.getFirst("x-user-id"))

      (tenantId, userId) match {
        case (Some(tid), Some(uid)) =>
          val user = AuthenticatedUser(tid, uid)
          request.exchange.putAttachment(AuthenticatedUserKey, user)
          delegate(request, Map.empty)

        case _ =>
          Log.warn(
            "Authentication failed: Missing identity headers",
            Map("url.path" -> request.exchange.getRequestPath)
          )
          Result.Success(
            Response(
              write(AuthError("Missing required headers: x-tenant-id, x-user-id", "unauthorized")),
              401,
              Seq("Content-Type" -> "application/json")
            )
          )
      }
    }
  }

  // ============================================================================
  // withRequestLog - Simple request/response logging
  // ============================================================================

  /**
   * Logs request and response details: method, path, status, duration.
   */
  class withRequestLog extends cask.RawDecorator {

    def wrapFunction(request: cask.Request, delegate: Delegate): Result[Raw] = {
      val startTime = System.nanoTime()
      val result = delegate(request, Map.empty)
      val durationMs = (System.nanoTime() - startTime) / 1_000_000.0

      val method = request.exchange.getRequestMethod.toString
      val path = request.exchange.getRequestPath

      result match {
        case Result.Success(response) =>
          Log.info(s"$method $path ${response.statusCode} ${durationMs}ms")

        case Result.Error.Exception(throwable) =>
          Log.error(s"$method $path 500 ${durationMs}ms - ${throwable.getMessage}")

        case _: Result.Error.InvalidArguments =>
          Log.error(s"$method $path 400 ${durationMs}ms - Invalid arguments")

        case _: Result.Error.MismatchedArguments =>
          Log.error(s"$method $path 400 ${durationMs}ms - Mismatched arguments")
      }

      result
    }
  }
}
