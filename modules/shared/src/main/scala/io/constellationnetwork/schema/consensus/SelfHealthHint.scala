package io.constellationnetwork.schema.consensus

import cats.{Eq, Order, Show}

import scala.util.Try

import enumeratum._
import io.circe._

/** A peer's self-reported consensus health classification.
  *
  * The type is shared because it is part of v35 ProposalValue and trusted checkpoint state. Runtime health sampling remains node-shared;
  * only the stable three-value wire vocabulary lives here.
  */
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
