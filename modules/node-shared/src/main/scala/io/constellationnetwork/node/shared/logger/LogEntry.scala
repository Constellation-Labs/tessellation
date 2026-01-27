package io.constellationnetwork.node.shared.logger

import io.circe._
import io.circe.syntax._

case class LogEntry(
  logType: String,
  data: Json,
  context: LogContext,
  elapsedMs: Option[Long]
)

object LogEntry {

  implicit val encoder: Encoder[LogEntry] = Encoder.instance { entry =>
    val ctxJson = entry.context.asJson.dropNullValues

    entry.data.asObject match {
      case Some(obj) =>
        val withCtx = obj.add("_ctx", ctxJson)
        val withMs = entry.elapsedMs.fold(withCtx)(ms => withCtx.add("_ms", Json.fromLong(ms)))
        Json.fromJsonObject(withMs)

      case None =>
        Json.obj(
          "_ctx" -> ctxJson,
          "_ms" -> entry.elapsedMs.asJson,
          "data" -> entry.data
        )
    }
  }
}
