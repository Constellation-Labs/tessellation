package org.tesselation.schema

import cats.effect.Concurrent

import org.tesselation.crypto.Signed
import org.tesselation.crypto.hash.Hash
import org.tesselation.ext.codecs.BinaryCodec
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.ID.Id

import org.http4s.{EntityDecoder, EntityEncoder}

object gossip {

  type RumorEntry = (Hash, Signed[Rumor])
  type RumorBatch = List[RumorEntry]

  case class ReceivedRumor[A](origin: Id, content: A)

  case class Rumor(
    tpe: String,
    content: Array[Byte]
  )

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
