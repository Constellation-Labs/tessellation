package org.tessellation.schema

import cats.kernel.Monoid
import cats.syntax.show._
import cats.{Order, Show}

import scala.reflect.runtime.universe.{TypeTag, typeOf}

import org.tessellation.schema.generation.Generation
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.Encodable
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import derevo.scalacheck.arbitrary
import eu.timepit.refined.scalacheck.numeric._
import eu.timepit.refined.types.numeric.PosLong
import io.circe.Json
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._

object gossip {

  @derive(arbitrary, order, show, encoder, decoder)
  case class Ordinal(generation: Generation, counter: PosLong)

  object Ordinal {

    val MinValue: Ordinal = Ordinal(Generation.MinValue, PosLong.MinValue)

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

  final case class CommonRumorRaw(
    content: Json,
    contentType: ContentType
  ) extends RumorRaw {
    override def toEncode: AnyRef = content.noSpacesSortKeys ++ contentType.coerce[String]
  }

  object CommonRumorRaw {
    implicit val show: Show[CommonRumorRaw] = (t: CommonRumorRaw) => s"CommonRumorRaw(contentType=${t.contentType.show})"
  }

  final case class PeerRumorRaw(
    origin: PeerId,
    ordinal: Ordinal,
    content: Json,
    contentType: ContentType
  ) extends RumorRaw {
    override def toEncode: AnyRef =
      content.noSpacesSortKeys ++ contentType.coerce[String] ++ origin.coerce[Hex].coerce[String] ++ ordinal.counter
        .toString() ++ ordinal.generation.value.toString()
  }

  object PeerRumorRaw {
    implicit val show: Show[PeerRumorRaw] = (t: PeerRumorRaw) =>
      s"PeerRumorRaw(origin=${t.origin.show}, ordinal=${t.ordinal.show}, contentType=${t.contentType.show})"
  }

  case class UnexpectedRumorClass(rumor: RumorRaw) extends Throwable(s"Unexpected rumor class ${rumor.show}")

  @derive(encoder, decoder)
  case class RumorOfferResponse(
    offer: List[Hash]
  )

  @derive(encoder, decoder)
  case class RumorInquiryRequest(
    inquiry: List[Hash]
  )

}
