package io.constellationnetwork.node.shared.http.routes

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.gossip.event._
import io.constellationnetwork.node.shared.infrastructure.mempool.{EventMempool, MempoolRejectionReason}
import io.constellationnetwork.routes.internal._

import eu.timepit.refined.auto._
import io.circe.{Decoder, Encoder}
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** HTTP routes for event gossip P2P communication.
  *
  * Provides endpoints for:
  *   - POST /events/push - Receive pushed events from peers
  *   - GET /events/ihave - Get hashes of events we have
  *   - POST /events/iwant - Request specific events by hash
  *
  * @param triggerEventConsensus
  *   Optional callback to trigger consensus when events are received
  * @tparam Key
  *   The state key type (unused by routes, but required for EventMempool type)
  */
final case class EventGossipRoutes[F[_]: Async, Event: Encoder: Decoder, Key](
  mempool: EventMempool[F, Event, Key],
  triggerEventConsensus: Option[F[Unit]] = None
) extends Http4sDsl[F]
    with P2PRoutes[F] {

  private val logger = Slf4jLogger.getLoggerFromName[F]("EventGossipRoutes")

  protected val prefixPath: InternalUrlPrefix = "/events"

  protected val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "push" =>
      for {
        push <- req.as[EventPush[Event]]
        _ <- logger.debug(s"[EventGossip] Received push for event ${push.eventHash.show}")
        result <- handlePush(push)
        _ <- result.fold(
          error => logger.debug(s"[EventGossip] Push rejected: $error"),
          _ => logger.debug(s"[EventGossip] Push accepted for event ${push.eventHash.show}")
        )
        response <- result.fold(
          error => BadRequest(error),
          _ => Ok(())
        )
      } yield response

    case GET -> Root / "ihave" =>
      for {
        snapshot <- mempool.snapshot()
        ihave = IHave(snapshot.hashes)
        _ <- logger.debug(s"[EventGossip] IHAVE request, returning ${ihave.hashes.size} hashes")
        response <- Ok(ihave)
      } yield response

    case req @ POST -> Root / "iwant" =>
      for {
        iwant <- req.as[IWantRequest]
        _ <- logger.debug(s"[EventGossip] IWANT request for ${iwant.hashes.size} hashes")
        events <- mempool.getMultiple(iwant.hashes)
        _ <- logger.debug(s"[EventGossip] IWANT response: found ${events.size}/${iwant.hashes.size} events")
        batch = events.toList.map { case (hash, hashed) => (hash, hashed.signed) }
        response <- Ok(IWantResponse(batch))
      } yield response
  }

  private def handlePush(push: EventPush[Event]): F[Either[String, Unit]] =
    mempool
      .add(push.event)
      .flatTap(_.traverse_(_ => triggerEventConsensus.traverse_(identity)))
      .map(_.bimap(MempoolRejectionReason.show.show, _ => ()))
}

object EventGossipRoutes {

  def make[F[_]: Async, Event: Encoder: Decoder, Key](
    mempool: EventMempool[F, Event, Key],
    triggerEventConsensus: Option[F[Unit]] = None
  ): EventGossipRoutes[F, Event, Key] =
    new EventGossipRoutes[F, Event, Key](mempool, triggerEventConsensus)
}
