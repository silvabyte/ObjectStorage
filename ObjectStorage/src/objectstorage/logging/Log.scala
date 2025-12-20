package objectstorage.logging

import scribe._
import scribe.writer._
import scribe.output.format.OutputFormat
import ujson._
import java.time.Instant
import objectstorage.config.Config

/**
 * JSON-formatted log writer for structured logging.
 *
 * Outputs logs as JSON objects with timestamp, level, message, source location, and custom data.
 * Supports pretty-printing with colors for development.
 */
class JsonWriter(pretty: Boolean = false) extends Writer {
  private val base64DataUrlPattern = """^data:[^;]+;base64,(.+)$""".r
  private val maxBase64Length = 100

  private def truncateBase64(value: String): String = {
    value match {
      case base64DataUrlPattern(data) =>
        val prefix = value.substring(0, value.indexOf(",") + 1)
        if (data.length > maxBase64Length) {
          s"${prefix}${data.take(maxBase64Length)}... [truncated ${data.length - maxBase64Length} chars]"
        } else {
          value
        }
      case _ if value.startsWith("data:") && value.contains("base64,") =>
        val idx = value.indexOf("base64,") + 7
        if (value.length - idx > maxBase64Length) {
          s"${value.take(idx + maxBase64Length)}... [truncated ${value.length - idx - maxBase64Length} chars]"
        } else {
          value
        }
      case _ => value
    }
  }

  private def sanitizeValue(json: ujson.Value): ujson.Value = {
    json match {
      case ujson.Obj(obj) =>
        ujson.Obj.from(obj.map { case (k, v) => k -> sanitizeValue(v) })
      case ujson.Arr(arr) =>
        ujson.Arr.from(arr.map(sanitizeValue))
      case ujson.Str(value) =>
        ujson.Str(truncateBase64(value))
      case other => other
    }
  }

  private def colorize(json: ujson.Value, level: String): String = {
    val keyColor = if (level == "ERROR") Console.RED else Console.YELLOW
    val valueColor = if (level == "ERROR") Console.RED else Console.GREEN

    json match {
      case ujson.Obj(obj) =>
        val coloredObj = obj
          .map { case (k, v) =>
            s"$keyColor\"$k\"${Console.RESET}: ${colorize(v, level)}"
          }
          .mkString("{\n  ", ",\n  ", "\n}")
        coloredObj
      case ujson.Arr(arr) =>
        val coloredArr = arr.map(colorize(_, level)).mkString("[", ", ", "]")
        coloredArr
      case ujson.Str(value) => s"$valueColor\"$value\"${Console.RESET}"
      case ujson.Num(value) => s"${Console.CYAN}$value${Console.RESET}"
      case ujson.Bool(value) =>
        if (value) s"${Console.MAGENTA}true${Console.RESET}"
        else s"${Console.MAGENTA}false${Console.RESET}"
      case ujson.Null => s"${Console.BLUE}null${Console.RESET}"
    }
  }

  override def write[M](
      record: LogRecord[M],
      output: scribe.output.LogOutput,
      outputFormat: OutputFormat
  ): Unit = {
    val json = Obj(
      "timestamp" -> Option(record.timeStamp).map(_.toString).getOrElse(""),
      "level" -> Option(record.level).map(_.name).getOrElse("INFO"),
      "message" -> Option(record.message.value).map(_.toString).getOrElse(""),
      "fileName" -> Option(record.fileName).getOrElse(""),
      "className" -> Option(record.className).getOrElse(""),
      "methodName" -> record.methodName.getOrElse(""),
      "line" -> record.line.getOrElse(-1),
      "thread" -> Option(record.thread).map(_.getName).getOrElse(""),
      "data" -> Obj.from(
        record.data.map { case (k, v) =>
          k -> Option(v())
            .map {
              case s: String => ujson.Str(s)
              case i: Int => ujson.Num(i)
              case l: Long => ujson.Num(l)
              case d: Double => ujson.Num(d)
              case b: Boolean => ujson.Bool(b)
              case other => ujson.Str(other.toString)
            }
            .getOrElse(ujson.Null)
        }
      )
    )

    val sanitizedJson = sanitizeValue(json)

    try {
      val outputStr = {
        if (pretty) colorize(sanitizedJson, sanitizedJson("level").str)
        else ujson.write(sanitizedJson)
      }
      println(outputStr)
    } catch {
      case ex: Exception =>
        println(s"Failed to write log: ${ex.getMessage}")
    }
  }
}

/**
 * Structured JSON logging utility.
 *
 * Provides a simple API for logging with structured data, similar to pino in Node.js.
 *
 * Usage:
 * {{{
 * Log.info("User logged in", Map("userId" -> "123", "ip" -> "192.168.1.1"))
 * Log.error("Failed to process request", Map("error" -> ex.getMessage))
 * }}}
 */
object Log {
  // Lazy initialization to allow Config to be set up first
  private lazy val jsonWriter = JsonWriter(pretty = Config.LOG_PRETTY)

  private lazy val logger = Logger.root
    .clearHandlers()
    .withHandler(writer = jsonWriter)
    .replace()

  def makeRecord(
      message: String,
      level: Level = Level.Info,
      data: Map[String, Any] = Map.empty
  )(implicit
      pkg: sourcecode.Pkg,
      fileName: sourcecode.FileName,
      name: sourcecode.Name,
      line: sourcecode.Line
  ): LogRecord[String] = {
    LogRecord.simple(
      timeStamp = Instant.now.toEpochMilli(),
      level = level,
      message = message,
      fileName = fileName.value,
      className = LoggerSupport.className(pkg, fileName)._2,
      methodName = Some(name.value),
      line = Some(line.value),
      data = data.map { case (key, value) => key -> (() => value) }
    )
  }

  def info(
      message: String,
      data: Map[String, Any] = Map.empty
  )(implicit
      pkg: sourcecode.Pkg,
      fileName: sourcecode.FileName,
      name: sourcecode.Name,
      line: sourcecode.Line
  ): Unit = {
    val record = makeRecord(message, Level.Info, data)
    logger.log(record)
  }

  def error(
      message: String,
      data: Map[String, Any] = Map.empty
  )(implicit
      pkg: sourcecode.Pkg,
      fileName: sourcecode.FileName,
      name: sourcecode.Name,
      line: sourcecode.Line
  ): Unit = {
    val record = makeRecord(message, Level.Error, data)
    logger.log(record)
  }

  def warn(
      message: String,
      data: Map[String, Any] = Map.empty
  )(implicit
      pkg: sourcecode.Pkg,
      fileName: sourcecode.FileName,
      name: sourcecode.Name,
      line: sourcecode.Line
  ): Unit = {
    val record = makeRecord(message, Level.Warn, data)
    logger.log(record)
  }

  def debug(
      message: String,
      data: Map[String, Any] = Map.empty
  )(implicit
      pkg: sourcecode.Pkg,
      fileName: sourcecode.FileName,
      name: sourcecode.Name,
      line: sourcecode.Line
  ): Unit = {
    val record = makeRecord(message, Level.Debug, data)
    logger.log(record)
  }
}
