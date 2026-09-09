package io.constellationnetwork.node.shared.http.routes

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.gossip.event._
import io.constellationnetwork.node.shared.infrastructure.mempool.{EventMempool, MempoolRejectionReason}
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.security.HasherSelector
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed._

import eu.timepit.refined.auto._
import io.circe.{Decoder, Encoder}
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl

/** HTTP routes for event gossip P2P communication.
  *
  * Provides endpoints for:
  *   - POST /events/push - Receive pushed events from peers
  *   - GET /events/ihave - Get hashes of events we have
  *   - POST /events/ihave - Check a request-specific set of hashes without snapshot truncation
  *   - GET /events/chain-tip - Get only the current chain tip
  *   - POST /events/iwant - Request specific events by hash
  *
  * @tparam Key
  *   The state key type (unused by routes, but required for EventMempool type)
  */
final case class EventGossipRoutes[F[_]: Async: HasherSelector, Event: Encoder: Decoder, Key](
  mempool: EventMempool[F, Event, Key],
  getLocalChainTip: Option[F[Option[ChainTip]]] = None,
  maybeMarkSeen: Option[Hash => F[Unit]] = None
) extends Http4sDsl[F]
    with P2PRoutes[F] {

  protected val prefixPath: InternalUrlPrefix = "/events"

  protected val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "push" =>
      for {
        push <- req.as[EventPush[Event]]
        result <- handlePush(push)
        response <- result.fold(
          error => BadRequest(error),
          _ => Ok(())
        )
      } yield response

    case GET -> Root / "ihave" =>
      for {
        snapshot <- mempool.snapshot()
        chainTip <- getLocalChainTip.fold(none[ChainTip].pure[F])(identity)
        ihave = IHave(snapshot.hashes, chainTip)
        response <- Ok(ihave)
      } yield response

    case req @ POST -> Root / "ihave" =>
      for {
        requested <- req.as[IWantRequest]
        response <-
          if (requested.hashes.size > EventMempool.DefaultSnapshotLimit)
            BadRequest(s"At most ${EventMempool.DefaultSnapshotLimit} event hashes may be checked")
          else
            for {
              present <- mempool.getMultiple(requested.hashes)
              chainTip <- getLocalChainTip.fold(none[ChainTip].pure[F])(identity)
              response <- Ok(IHave(present.keySet, chainTip))
            } yield response
      } yield response

    // Chain-tip-only probe. Reuse the exact payload/source carried by IHave without forcing a
    // full mempool snapshot and event-hash serialization on every admission monitor tick.
    case GET -> Root / "chain-tip" =>
      getLocalChainTip.fold(none[ChainTip].pure[F])(identity).flatMap(Ok(_))

    case req @ POST -> Root / "iwant" =>
      for {
        iwant <- req.as[IWantRequest]
        response <-
          if (iwant.hashes.size > EventGossipBounds.MaxIWantRequestHashes)
            BadRequest(s"At most ${EventGossipBounds.MaxIWantRequestHashes} events may be requested")
          else
            for {
              events <- mempool.getMultiple(iwant.hashes)
              batch = EventGossipRoutes.boundIWantBatch(events.toList.map { case (hash, hashed) => (hash, hashed.signed) })
              response <- Ok(IWantResponse(batch))
            } yield response
      } yield response
  }

  private def handlePush(push: EventPush[Event]): F[Either[String, Unit]] =
    HasherSelector[F].withCurrent { implicit hasher =>
      push.event.toHashed.flatMap { canonical =>
        if (canonical.hash =!= push.eventHash)
          s"Event push hash mismatch: declared=${push.eventHash.show}, canonical=${canonical.hash.show}".asLeft[Unit].pure[F]
        else if (!EventGossipBounds.isPullable(canonical.hash, push.event))
          s"Event exceeds the bounded IWANT response size: maxBytes=${EventGossipBounds.MaxIWantResponseBytes}".asLeft[Unit].pure[F]
        else
          mempool
            .add(push.event)
            .flatTap {
              // Mark seen on successful add so the pull loop does not issue redundant IWANT
              // requests for events already received via push.
              case Right(entry) => maybeMarkSeen.fold(Async[F].unit)(_(entry.hashed.hash))
              case Left(_)      => Async[F].unit
            }
            .map(_.bimap(MempoolRejectionReason.show.show, _ => ()))
      }
    }
}

object EventGossipRoutes {

  /** IWANT returns event bodies, unlike IHAVE's fixed-size hashes. Keep both count and encoded response size bounded so a single
    * authenticated request cannot materialize a multi-gigabyte Currency event response.
    */
  private[http] def boundIWantBatch[Event: Encoder](
    events: List[(Hash, io.constellationnetwork.security.signature.Signed[Event])]
  ): List[(Hash, io.constellationnetwork.security.signature.Signed[Event])] =
    events
      .take(EventGossipBounds.MaxIWantRequestHashes)
      .foldLeft(List.empty[(Hash, io.constellationnetwork.security.signature.Signed[Event])]) {
        case (accepted, _) if accepted.size >= EventGossipBounds.MaxIWantResponseEvents => accepted
        case (accepted, event) =>
          val candidate = accepted :+ event
          val encodedBytes = EventGossipBounds.encodedResponseBytes(candidate)
          if (encodedBytes <= EventGossipBounds.MaxIWantResponseBytes) candidate else accepted
      }

  def make[F[_]: Async: HasherSelector, Event: Encoder: Decoder, Key](
    mempool: EventMempool[F, Event, Key],
    getLocalChainTip: Option[F[Option[ChainTip]]] = None,
    maybeMarkSeen: Option[Hash => F[Unit]] = None
  ): EventGossipRoutes[F, Event, Key] =
    new EventGossipRoutes[F, Event, Key](mempool, getLocalChainTip, maybeMarkSeen)
}
