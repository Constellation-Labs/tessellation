package org.tessellation.schema

import cats.effect.Concurrent
import cats.kernel.Monoid
import cats.syntax.show._
import cats.{Order, Show}

import scala.reflect.runtime.universe.{TypeTag, typeOf}

import org.tessellation.ext.codecs.BinaryCodec
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, order, show}
import derevo.derive
import derevo.scalacheck.arbitrary
import eu.timepit.refined.scalacheck.numeric._
import eu.timepit.refined.types.numeric.PosLong
import io.estatico.newtype.macros.newtype
import org.http4s.{EntityDecoder, EntityEncoder}

object gossip {

  @derive(arbitrary, eqv, show, order)
  case class Ordinal(generation: PosLong, counter: PosLong)

  object Ordinal {

    val MinValue: Ordinal = Ordinal(PosLong.MinValue, PosLong.MinValue)

    implicit val maxMonoid: Monoid[Ordinal] = Monoid.instance(MinValue, (a, b) => Order[Ordinal].max(a, b))

  }

  @derive(arbitrary, eqv, show)
  @newtype
  case class ContentType(value: String)

  object ContentType {
    def of[A: TypeTag]: ContentType = ContentType(typeOf[A].dealias.toString)
  }

  type HashAndRumor = (Hash, Signed[RumorBinary])
  type RumorBatch = List[HashAndRumor]

  case class PeerRumor[A](origin: PeerId, ordinal: Ordinal, content: A)

  case class CommonRumor[A](content: A)

  sealed trait RumorBinary {
    val content: Array[Byte]
    val contentType: ContentType
  }

  object RumorBinary {
    implicit val show: Show[RumorBinary] = {
      case r: CommonRumorBinary => Show[CommonRumorBinary].show(r)
      case r: PeerRumorBinary   => Show[PeerRumorBinary].show(r)
    }
  }

  final case class CommonRumorBinary(
    content: Array[Byte],
    contentType: ContentType
  ) extends RumorBinary

  object CommonRumorBinary {
    implicit val show: Show[CommonRumorBinary] = (t: CommonRumorBinary) =>
      s"CommonRumorBinary(contentType=${t.contentType.show})"
  }

  @derive(arbitrary)
  final case class PeerRumorBinary(
    origin: PeerId,
    ordinal: Ordinal,
    content: Array[Byte],
    contentType: ContentType
  ) extends RumorBinary

  object PeerRumorBinary {
    implicit val show: Show[PeerRumorBinary] = (t: PeerRumorBinary) =>
      s"PeerRumorBinary(origin=${t.origin.show}, ordinal=${t.ordinal.show}, contentType=${t.contentType.show})"
  }

  case class UnexpectedRumorClass(rumor: RumorBinary) extends Throwable(s"Unexpected rumor class ${rumor.show}")

  case class StartGossipRoundRequest(
    offer: List[Hash]
  )

  object StartGossipRoundRequest {
    implicit def encoder[G[_]: KryoSerializer]: EntityEncoder[G, StartGossipRoundRequest] =
      BinaryCodec.encoder[G, StartGossipRoundRequest]

    implicit def decoder[G[_]: Concurrent: KryoSerializer]: EntityDecoder[G, StartGossipRoundRequest] =
      BinaryCodec.decoder[G, StartGossipRoundRequest]
  }

  case class StartGossipRoundResponse(
    inquiry: List[Hash],
    offer: List[Hash]
  )

  object StartGossipRoundResponse {
    implicit def encoder[G[_]: KryoSerializer]: EntityEncoder[G, StartGossipRoundResponse] =
      BinaryCodec.encoder[G, StartGossipRoundResponse]

    implicit def decoder[G[_]: Concurrent: KryoSerializer]: EntityDecoder[G, StartGossipRoundResponse] =
      BinaryCodec.decoder[G, StartGossipRoundResponse]
  }

  case class EndGossipRoundRequest(
    answer: RumorBatch,
    inquiry: List[Hash]
  )

  object EndGossipRoundRequest {
    implicit def encoder[G[_]: KryoSerializer]: EntityEncoder[G, EndGossipRoundRequest] =
      BinaryCodec.encoder[G, EndGossipRoundRequest]

    implicit def decoder[G[_]: Concurrent: KryoSerializer]: EntityDecoder[G, EndGossipRoundRequest] =
      BinaryCodec.decoder[G, EndGossipRoundRequest]
  }

  case class EndGossipRoundResponse(
    answer: RumorBatch
  )

  object EndGossipRoundResponse {
    implicit def encoder[G[_]: KryoSerializer]: EntityEncoder[G, EndGossipRoundResponse] =
      BinaryCodec.encoder[G, EndGossipRoundResponse]

    implicit def decoder[G[_]: Concurrent: KryoSerializer]: EntityDecoder[G, EndGossipRoundResponse] =
      BinaryCodec.decoder[G, EndGossipRoundResponse]
  }

}
