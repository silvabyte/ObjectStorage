package objectstorage.decorators

import cask.model.Response.Raw
import cask.model.{Request, Response}
import cask.router.Result
import objectstorage.logging.Log

/** Cask middleware decorators for request/response handling.
  *
  * Note: Header validation (x-tenant-id, x-user-id) is now handled via BoogieLoops schema validation in RouteSchema.headers.
  * The withUserIdAndTenantId decorator has been removed in favor of schema-based validation.
  */
object Decorators {

  /** Logs request and response details including timing, status, and errors.
    *
    * This decorator can be stacked with @Web.* annotations to add request logging.
    *
    * Example usage:
    * {{{
    * @withResponseLog
    * @Web.get("/api/v1/files", RouteSchema(...))
    * def listFiles(r: ValidatedRequest): Response[String] = ...
    * }}}
    */
  class withResponseLog extends cask.RawDecorator {

    def wrapFunction(request: cask.Request, delegate: Delegate): Result[Raw] = {
      val startTime = System.nanoTime()
      val result = delegate(request, Map.empty)
      val responseTimeNanos = System.nanoTime() - startTime
      val responseTimeMillis = responseTimeNanos / 1000000.0

      val baseMeta = Map(
        "url.path"            -> request.exchange.getRequestPath,
        "url.query"           -> request.exchange.getQueryString,
        "url.full"            -> request.exchange.getRequestURL,
        "client.ip"           -> request.exchange.getSourceAddress.getAddress.getAddress.toString,
        "client.port"         -> request.exchange.getSourceAddress.getPort,
        "user_agent.original" -> request.exchange.getRequestHeaders.getFirst("user-agent"),
        "request.method"      -> request.exchange.getRequestMethod,
        "request.id"          -> request.exchange.getRequestId,
        "request-id"          -> request.exchange.getRequestHeaders.getFirst("request-id"),
        "x-tenant-id"         -> request.exchange.getRequestHeaders.getFirst("x-tenant-id"),
        "x-user-id"           -> request.exchange.getRequestHeaders.getFirst("x-user-id"),
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
