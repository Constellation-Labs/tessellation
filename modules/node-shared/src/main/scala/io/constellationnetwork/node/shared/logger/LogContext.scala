package io.constellationnetwork.node.shared.logger

import io.constellationnetwork.schema.SnapshotOrdinal

import io.circe._
import io.circe.generic.semiauto._

case class LogContext(
  ordinal: Option[SnapshotOrdinal] = None,
  correlationId: Option[String] = None,
  operation: Option[String] = None,
  phase: Option[String] = None,
  startTimeMs: Option[Long] = None
) {
  def withOrdinal(o: SnapshotOrdinal): LogContext = copy(ordinal = Some(o))
  def withCorrelationId(id: String): LogContext = copy(correlationId = Some(id))
  def withOperation(op: String): LogContext = copy(operation = Some(op))
  def withPhase(p: String): LogContext = copy(phase = Some(p))
  def withStartTime(t: Long): LogContext = copy(startTimeMs = Some(t))
  def elapsedMs(now: Long): Option[Long] = startTimeMs.map(now - _)
}

object LogContext {
  val empty: LogContext = LogContext()

  implicit val ordinalEncoder: Encoder[SnapshotOrdinal] =
    Encoder.instance(o => Json.fromLong(o.value.value))

  implicit val encoder: Encoder[LogContext] = deriveEncoder
}
