package org.tessellation.sdk.http.routes

import cats.Order._
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip._
import org.tessellation.sdk.domain.gossip.{Gossip, RumorStorage}
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.security.signature.Signed

import fs2.Stream
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl._
import org.http4s.server.Router

final case class GossipRoutes[F[_]: Async: KryoSerializer: Metrics](
  rumorStorage: RumorStorage[F],
  gossip: Gossip[F]
) extends Http4sDsl[F] {

  private val prefixPath = "/gossip"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "offer" =>
      for {
        offer <- rumorStorage.getActiveHashes
        beginResponse = RumorOfferResponse(offer)
        result <- Ok(beginResponse)
      } yield result

    case req @ POST -> Root / "inquiry" =>
      for {
        inquiryRequest <- req.as[RumorInquiryRequest]
        rumors <- rumorStorage.getRumors(inquiryRequest.inquiry)
        sortedRumors = sortRumors(rumors)
        result <- Ok(Stream.emits(sortedRumors).covary[F])
      } yield result

  }

  private def sortRumors(batch: List[Signed[RumorRaw]]): List[Signed[RumorRaw]] =
    batch.filter(_.value.isInstanceOf[CommonRumorRaw]) ++ batch
      .filter(_.value.isInstanceOf[PeerRumorRaw])
      .sortBy(_.value.asInstanceOf[PeerRumorRaw].ordinal)

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}
