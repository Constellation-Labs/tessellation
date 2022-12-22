package org.tessellation.schema

import cats.kernel.{Monoid, Next, PartialOrder}
import cats.syntax.monoid._
import cats.syntax.show._
import cats.{Order, Show}

import scala.reflect.runtime.universe.{TypeTag, typeOf}

import org.tessellation.schema.generation.Generation
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.Encodable
import org.tessellation.schema.security.hash.Hash
import org.tessellation.schema.security.hex.Hex
import org.tessellation.schema.security.signature.Signed

import derevo.cats.{order, show}
import derevo.circe.magnolia._
import derevo.derive
import derevo.scalacheck.arbitrary
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.scalacheck.numeric._
import eu.timepit.refined.types.numeric.PosLong
import io.circe.Json
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._

object gossip {

  @derive(arbitrary, order, show, encoder, decoder)
  @newtype
  case class Counter(value: PosLong)

  object Counter {

    val MinValue: Counter = Counter(PosLong.MinValue)

    implicit val next: Next[Counter] = new Next[Counter] {
      override def next(a: Counter): Counter = Counter(a.value |+| 1L)

      override def partialOrder: PartialOrder[Counter] = Order[Counter]
    }
  }

  @derive(arbitrary, order, show, encoder, decoder)
  case class Ordinal(generation: Generation, counter: Counter)

  object Ordinal {

    val MinValue: Ordinal = Ordinal(Generation.MinValue, Counter.MinValue)

    implicit val maxMonoid: Monoid[Ordinal] = Monoid.instance(MinValue, (a, b) => Order[Ordinal].max(a, b))

  }

  @derive(arbitrary, order, show, encoder, decoder)
  @newtype
  case class ContentType(value: String)

  object ContentType {
    def of[A: TypeTag]: ContentType = ContentType(typeOf[A].dealias.toString)
  }

  type HashAndRumor = (Hash, Signed[RumorRaw])
  type RumorBatch = List[HashAndRumor]

  case class PeerRumor[A](origin: PeerId, ordinal: Ordinal, content: A)

  case class CommonRumor[A](content: A)

  @derive(encoder, decoder)
  sealed trait RumorRaw extends Encodable {
    val content: Json
    val contentType: ContentType
  }

  object RumorRaw {
    implicit val show: Show[RumorRaw] = {
      case r: CommonRumorRaw => Show[CommonRumorRaw].show(r)
      case r: PeerRumorRaw   => Show[PeerRumorRaw].show(r)
    }
  }

  @derive(encoder, decoder)
  final case class CommonRumorRaw(
    content: Json,
    contentType: ContentType
  ) extends RumorRaw {
    override def toEncode: AnyRef = content.noSpacesSortKeys ++ contentType.coerce[String]
  }

  object CommonRumorRaw {
    implicit val show: Show[CommonRumorRaw] = (t: CommonRumorRaw) => s"CommonRumorRaw(contentType=${t.contentType.show})"
  }

  @derive(encoder, decoder)
  final case class PeerRumorRaw(
    origin: PeerId,
    ordinal: Ordinal,
    content: Json,
    contentType: ContentType
  ) extends RumorRaw {
    override def toEncode: AnyRef =
      content.noSpacesSortKeys ++ contentType
        .coerce[String] ++ origin.coerce[Hex].coerce[String] ++ ordinal.counter.toString ++ ordinal.generation.toString
  }

  object PeerRumorRaw {
    implicit val show: Show[PeerRumorRaw] = (t: PeerRumorRaw) =>
      s"PeerRumorRaw(origin=${t.origin.show}, ordinal=${t.ordinal.show}, contentType=${t.contentType.show})"
  }

  case class UnexpectedRumorClass(rumor: RumorRaw) extends Throwable(s"Unexpected rumor class ${rumor.show}")

  @derive(encoder, decoder)
  case class PeerRumorInquiryRequest(
    ordinals: Map[PeerId, Ordinal]
  )

  @derive(encoder, decoder)
  case class CommonRumorOfferResponse(
    offer: Set[Hash]
  )

  @derive(encoder, decoder)
  case class QueryCommonRumorsRequest(
    query: Set[Hash]
  )

  @derive(encoder, decoder)
  case class CommonRumorInitResponse(
    seen: Set[Hash]
  )

}
