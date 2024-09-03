package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Show
import cats.effect.Sync
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._

import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusWithdrawPeerDeclaration

import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

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
      .condModifyState(key) { state =>
        val declaration = getWithdrawalDeclaration(key, state)
        val effect =
          gossip.spread(declaration) >>
            logger.info(s"Withdrew from consensus {key=${declaration.key.show}, kind=${declaration.kind.show}}")

        (none[ConsensusState[Key, Status, Outcome, Kind]], effect).some.pure[F]
      }
      .flatMap(evalEffect)

  private def evalEffect(maybeEffect: Option[F[Unit]]): F[Unit] =
    maybeEffect.traverse(identity).flatMap(_.liftTo[F](new Throwable("Should never happen")))
}
