package org.tessellation.http.routes

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.ext.codecs.BinaryCodec._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip._
import org.tessellation.sdk.domain.gossip.{Gossip, RumorStorage}

import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class GossipRoutes[F[_]: Async: KryoSerializer](
  rumorStorage: RumorStorage[F],
  rumorQueue: Queue[F, RumorBatch],
  gossip: Gossip[F]
) extends Http4sDsl[F] {

  private val prefixPath = "/gossip"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "start" =>
      for {
        beginRequest <- req.as[StartGossipRoundRequest]
        activeHashes <- rumorStorage.getActiveHashes
        offer = activeHashes.diff(beginRequest.offer)
        seenHashes <- rumorStorage.getSeenHashes
        inquiry = beginRequest.offer.diff(seenHashes)
        beginResponse = StartGossipRoundResponse(inquiry, offer)
        result <- Ok(beginResponse)
      } yield result

    case req @ POST -> Root / "end" =>
      for {
        endRequest <- req.as[EndGossipRoundRequest]
        _ <- rumorQueue.offer(endRequest.answer)
        answer <- rumorStorage.getRumors(endRequest.inquiry)
        endResponse = EndGossipRoundResponse(answer)
        result <- Ok(endResponse)
      } yield result
  }

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}
