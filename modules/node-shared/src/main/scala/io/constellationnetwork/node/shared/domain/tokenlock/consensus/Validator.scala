package io.constellationnetwork.node.shared.domain.tokenlock.consensus

import cats.Applicative
import cats.effect.kernel.Async
import cats.syntax.all._

import io.constellationnetwork.currency.tokenlock.ConsensusInput
import io.constellationnetwork.node.shared.config.types.LastGlobalSnapshotsSyncConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage}
import io.constellationnetwork.node.shared.domain.tokenlock.TokenLockStorage
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import eu.timepit.refined.types.numeric.PosInt
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Validator {
  private def logger[F[_]: Async] = Slf4jLogger.getLogger[F]

  private def isReadyForConsensus(state: NodeState): Boolean = state == NodeState.Ready

  def isLastGlobalSnapshotPresent[F[_]: Async](
    lastGlobalSnapshot: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
  ): F[Boolean] =
    lastGlobalSnapshot.getOrdinal.map(_.isDefined)

  private def enoughPeersForConsensus[F[_]: Async](
    clusterStorage: ClusterStorage[F],
    peersCount: PosInt
  ): F[Boolean] =
    clusterStorage.getResponsivePeers
      .map(_.filter(p => isReadyForConsensus(p.state)))
      .map(_.size >= peersCount.value)

  def isPeerInputValid[F[_]: Async: SecurityProvider: Hasher](
    input: Signed[ConsensusInput.PeerConsensusInput]
  ): F[Boolean] =
    for {
      validSignature <- input.hasValidSignature
      signedExclusively = input.isSignedExclusivelyBy(PeerId._Id.get(input.value.senderId))
    } yield validSignature && signedExclusively

  private def atLeastOneTokenLock[F[_]](
    tokenLockStorage: TokenLockStorage[F]
  ): F[Boolean] = tokenLockStorage.areWaiting

  def canStartOwnTokenLockConsensus[F[_]: Async](
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    lastGlobalSnapshot: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    peersCount: PosInt,
    tokenLockStorage: TokenLockStorage[F]
  ): F[Boolean] =
    for {
      stateReadyForConsensus <- nodeStorage.getNodeState.map(isReadyForConsensus)
      enoughPeers <- enoughPeersForConsensus(clusterStorage, peersCount)
      lastGlobalSnapshotPresent <- isLastGlobalSnapshotPresent(lastGlobalSnapshot)
      enoughTokenLocks <- atLeastOneTokenLock(tokenLockStorage)

      res =
        stateReadyForConsensus && enoughPeers && lastGlobalSnapshotPresent && enoughTokenLocks
      _ <-
        Applicative[F].whenA(!res) {
          val reason = Seq(
            if (!stateReadyForConsensus) "State not ready for consensus" else "",
            if (!enoughPeers) "Not enough peers" else "",
            if (!lastGlobalSnapshotPresent) "No global snapshot" else "",
            if (!enoughTokenLocks) "No token locks" else ""
          ).filter(_.nonEmpty).mkString(", ")
          logger.debug(s"Cannot start token lock own consensus: ${reason}")
        }
    } yield res
}
