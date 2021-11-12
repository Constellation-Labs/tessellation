package org.tesselation.schema

import cats.effect.Concurrent
import cats.syntax.contravariant._
import cats.syntax.show._
import cats.{Order, Show}

import scala.reflect.runtime.universe.{TypeTag, typeOf}

import org.tesselation.ext.codecs.BinaryCodec
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.peer.PeerId
import org.tesselation.security.hash.Hash
import org.tesselation.security.signature.Signed

import derevo.cats.{eqv, order, show}
import derevo.derive
import derevo.scalacheck.arbitrary
import eu.timepit.refined.scalacheck.numeric._
import eu.timepit.refined.types.numeric.PosLong
import io.estatico.newtype.macros.newtype
import org.http4s.{EntityDecoder, EntityEncoder}

object gossip {

  @derive(arbitrary, eqv, show)
  @newtype
  case class ContentType(value: String)

  @derive(arbitrary, eqv, show, order)
  case class Ordinal(generation: PosLong, counter: PosLong)

  object ContentType {
    def of[A: TypeTag]: ContentType = ContentType(typeOf[A].toString)
  }

  type HashAndRumor = (Hash, Signed[Rumor])
  type RumorBatch = List[HashAndRumor]

  case class ReceivedRumor[A](origin: PeerId, content: A)

  @derive(arbitrary)
  case class Rumor(
    origin: PeerId,
    ordinal: Ordinal,
    content: Array[Byte],
    contentType: ContentType
  )

  object Rumor {
    implicit val orderInstances: Order[Rumor] = Order[Ordinal].contramap[Rumor](_.ordinal)

    implicit val showInstances: Show[Rumor] = (t: Rumor) =>
      s"Rumor(origin=${t.origin.show}, ordinal=${t.ordinal.show}, contentType=${t.contentType.show}, "
  }

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
