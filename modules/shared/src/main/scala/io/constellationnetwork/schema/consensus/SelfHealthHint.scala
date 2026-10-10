package io.constellationnetwork.schema.consensus

import cats.{Eq, Order, Show}

import scala.util.Try

import enumeratum._
import io.circe._

/** A node's local health classification, exported for operators only. It is not part of any consensus or wire type. */
sealed trait SelfHealthHint extends EnumEntry {
  def label: String = entryName
}

object SelfHealthHint extends Enum[SelfHealthHint] with SelfHealthHintCodecs {
  val values = findValues

  case object Healthy extends SelfHealthHint { override val entryName = "healthy" }
  case object Degraded extends SelfHealthHint { override val entryName = "degraded" }
  case object Critical extends SelfHealthHint { override val entryName = "critical" }

  implicit val show: Show[SelfHealthHint] = Show.show(_.entryName)
  implicit val eq: Eq[SelfHealthHint] = Eq.fromUniversalEquals

  implicit val order: Order[SelfHealthHint] = Order.by {
    case Healthy  => 0
    case Degraded => 1
    case Critical => 2
  }
  implicit val ordering: Ordering[SelfHealthHint] = order.toOrdering
}

trait SelfHealthHintCodecs {
  implicit val encode: Encoder[SelfHealthHint] = Encoder.encodeString.contramap(_.entryName)
  implicit val decode: Decoder[SelfHealthHint] = Decoder.decodeString.emapTry(s => Try(SelfHealthHint.withName(s)))
}
