package org.tessellation.sdk.infrastructure.consensus

import cats.Show
import cats.effect.Async
import cats.kernel.Next
import cats.syntax.applicative._
import cats.syntax.bifunctor._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.consensus.declaration.kind.{PeerDeclarationKind, _}
import org.tessellation.sdk.infrastructure.consensus.message.ConsensusWithdrawPeerDeclaration

import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait ConsensusStateRemover[F[_], Key, Artifact] {

  def withdrawFromConsensus(key: Key): F[Unit]

}

object ConsensusStateRemover {
  def make[F[
    _
  ]: Async, Event, Key: Show: Next: TypeTag: Encoder, Artifact](
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact],
    gossip: Gossip[F]
  ): ConsensusStateRemover[F, Key, Artifact] = new ConsensusStateRemover[F, Key, Artifact] {

    private val logger = Slf4jLogger.getLoggerFromClass(ConsensusStateRemover.getClass)

    def withdrawFromConsensus(key: Key): F[Unit] =
      consensusStorage
        .condModifyState(key)(toRemoveStateFn(key, withdrawFromConsensus))
        .flatMap(evalEffect)

    import consensusStorage.ModifyStateFn

    private def toRemoveStateFn(
      key: Key,
      fn: Either[Key, ConsensusState[Key, Artifact]] => F[Unit]
    ): ModifyStateFn[F[Unit]] = { maybeState =>
      val effect = fn(maybeState.toRight(key))
      (none[ConsensusState[Key, Artifact]], effect).some.pure[F]
    }

    private def evalEffect(maybeEffect: Option[F[Unit]]): F[Unit] =
      maybeEffect.traverse(identity).flatMap(_.liftTo[F](new Throwable("Should never happen")))

    private def withdrawFromConsensus(keyOrState: Either[Key, ConsensusState[Key, Artifact]]): F[Unit] = {
      val (withdrawalKey, withdrawalKind) = keyOrState.map { state =>
        state.status match {
          case _: CollectingFacilities[Artifact] => (state.key, Proposal)
          case _: CollectingProposals[Artifact]  => (state.key, MajoritySignature)
          case _: CollectingSignatures[Artifact] => (state.key.next, Facility)
          case _: Finished[Artifact]             => (state.key.next, Facility)
        }
      }.leftMap { key =>
        (key, Facility)
      }.leftWiden[(Key, PeerDeclarationKind)].merge

      gossip.spread(ConsensusWithdrawPeerDeclaration(withdrawalKey, withdrawalKind)) >>
        logger.info(s"Withdrew from consensus {key=${withdrawalKey.show}, kind=${withdrawalKind.show}}")
    }

  }
}
