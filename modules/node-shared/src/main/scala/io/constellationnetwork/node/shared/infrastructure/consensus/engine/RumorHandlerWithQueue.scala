package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.Applicative
import cats.data.{Kleisli, OptionT}
import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.all._

import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand._
import io.constellationnetwork.node.shared.infrastructure.gossip._
import io.constellationnetwork.schema.gossip._
import io.constellationnetwork.schema.peer.PeerId

import io.circe.Decoder

/** Bridges the gossip layer with the consensus command queue.
  *
  * ==Purpose==
  *
  * The gossip layer delivers rumors as a stream. This handler filters relevant consensus rumors and converts them to `RumorReceived`
  * commands for the FSM.
  *
  * ==Filtering==
  *
  * Only processes rumors that are relevant to consensus:
  *   - `ConsensusPeerDeclaration` - Peer's facility/proposal/signature
  *   - `ConsensusPeerDeclarationAck` - Acknowledgment of seen declarations
  *   - `ConsensusWithdrawPeerDeclaration` - Peer withdrawing
  *   - `ConsensusArtifact` - Artifact data
  *   - `ConsensusEvent` - Events that may trigger consensus
  *
  * ==Flow==
  * {{{
  *   Gossip Stream
  *       │
  *       ▼
  *   RumorHandlerWithQueue.rumorHandler
  *       │
  *       ├── Is consensus rumor? ──No──► Ignore
  *       │
  *       ├── Is from allowed peer? ──No──► Ignore
  *       │
  *       ▼
  *   queue.offer(RumorReceived(rumor))
  * }}}
  */
object RumorHandlerWithQueue {

  def peer[F[_]: Async, A: TypeTag: Decoder](
    queue: Queue[F, ConsensusCommand],
    selfOriginPolicy: OriginPolicy = IncludeSelfOrigin
  ): RumorHandler[F] = {
    val expectedType = ContentType.of[A]

    rumorHandler[F] {
      case (raw: PeerRumorRaw, selfId) if raw.contentType === expectedType && allowOrigin(raw.origin, selfId, selfOriginPolicy) =>
        for {
          decoded <- raw.content.as[A].liftTo[F]
          _ <- queue.offer(RumorReceived(Left(PeerRumor(raw.origin, raw.ordinal, decoded))))
        } yield ()
    }
  }

  def common[F[_]: Async, A: TypeTag: Decoder](
    queue: Queue[F, ConsensusCommand]
  ): RumorHandler[F] = {
    val expectedType = ContentType.of[A]

    rumorHandler[F] {
      case (raw: CommonRumorRaw, _) if raw.contentType === expectedType =>
        for {
          decoded <- raw.content.as[A].liftTo[F]
          _ <- queue.offer(RumorReceived(Right(CommonRumor(decoded))))
        } yield ()
    }
  }

  private def allowOrigin(origin: PeerId, selfId: PeerId, policy: OriginPolicy): Boolean =
    origin =!= selfId || policy =!= ExcludeSelfOrigin

  private def rumorHandler[F[_]: Applicative](
    pf: PartialFunction[(RumorRaw, PeerId), F[Unit]]
  ): RumorHandler[F] =
    Kleisli(input => OptionT(pf.lift(input).sequence))
}
