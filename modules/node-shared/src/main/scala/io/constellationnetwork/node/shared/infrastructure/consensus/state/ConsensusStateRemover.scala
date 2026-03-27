package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.Show
import cats.effect.Sync
import cats.syntax.all._

import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event => LogEvent}
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusWithdrawPeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.consensus.{ConsensusLog, ConsensusStorage}

import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Handles withdrawal from consensus participation.
  *
  * ==When Called==
  *
  * Called when node wants to leave consensus (e.g., shutting down, leaving cluster).
  *
  * ==What It Does==
  *
  *   1. Determines current consensus key and status 2. Creates appropriate withdrawal declaration based on current phase 3. Spreads
  *      ConsensusWithdrawPeerDeclaration via gossip
  *
  * ==Why Withdrawal Matters==
  *
  * If a peer just disappears, other peers will wait for their declarations forever (until stall detection kicks in). Explicit withdrawal
  * lets others proceed immediately.
  *
  * ==Subclassing==
  *
  * Abstract class. Subclasses implement:
  *   - `getWithdrawalDeclaration(key, state)` - Returns (declarationKey, declarationKind)
  */
abstract class ConsensusStateRemover[F[
  _
]: Sync, Key: TypeTag: Encoder: Show, Event, Artifact, Context, Status, Outcome, Kind: Encoder: Show: TypeTag](
  consensusStorage: ConsensusStorage[F, Event, Key, Artifact, Context, Status, Outcome, Kind],
  gossip: Gossip[F]
) {

  private val logger = Slf4jLogger.getLogger[F]

  protected def getWithdrawalDeclaration(
    key: Key,
    maybeState: Option[ConsensusState[Key, Status, Outcome, Kind]]
  ): ConsensusWithdrawPeerDeclaration[Key, Kind]

  def withdrawFromConsensus(key: Key): F[Unit] =
    consensusStorage
      .condModifyState(key) { maybeState =>
        val declaration = getWithdrawalDeclaration(key, maybeState)
        val effect =
          gossip.spread(declaration) >>
            ConsensusLog.info(
              logger,
              Category.Lifecycle,
              declaration.key.show,
              "n/a",
              LogEvent.Withdrew,
              "kind" -> declaration.kind.show
            )

        (none[ConsensusState[Key, Status, Outcome, Kind]], effect).some.pure[F]
      }
      .flatMap(evalEffect)

  private def evalEffect(maybeEffect: Option[F[Unit]]): F[Unit] =
    maybeEffect.traverse_(identity)
}
