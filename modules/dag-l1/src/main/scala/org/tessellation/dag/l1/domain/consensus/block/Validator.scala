package org.tessellation.dag.l1.domain.consensus.block

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, Monad}

import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.PeerBlockConsensusInput
import org.tessellation.dag.l1.domain.consensus.block.storage.ConsensusStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.node.NodeState.Ready
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto.autoUnwrap
import eu.timepit.refined.types.numeric.PosInt
import org.typelevel.log4cats.Logger

object Validator {

  def isReadyForBlockConsensus(state: NodeState): Boolean = state == Ready

  private def enoughPeersForConsensus[F[_]: Monad](
    clusterStorage: ClusterStorage[F],
    peersCount: PosInt
  ): F[Boolean] =
    clusterStorage.getPeers
      .map(_.filter(p => isReadyForBlockConsensus(p.state)))
      .map(_.size >= peersCount)

  private def enoughTipsForConsensus[F[_]: Monad](
    blockStorage: BlockStorage[F],
    tipsCount: PosInt
  ): F[Boolean] =
    blockStorage.getTips(tipsCount).map(_.isDefined)

  def canStartOwnConsensus[F[_]: Monad: Logger](
    consensusStorage: ConsensusStorage[F],
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    blockStorage: BlockStorage[F],
    peersCount: PosInt,
    tipsCount: PosInt
  ): F[Boolean] =
    for {
      noOwnRoundInProgress <- consensusStorage.ownConsensus.get.map(_.isEmpty)
      stateReadyForConsensus <- nodeStorage.getNodeState.map(isReadyForBlockConsensus)
      enoughPeers <- enoughPeersForConsensus(clusterStorage, peersCount)
      enoughTips <- enoughTipsForConsensus(blockStorage, tipsCount)

      res = noOwnRoundInProgress && stateReadyForConsensus && enoughPeers && enoughTips

      _ <- if (!res) {
        val reason = Seq(
          (if (!noOwnRoundInProgress) "Own round in progress" else ""),
          (if (!stateReadyForConsensus) "State not ready for consensus" else ""),
          (if (!enoughPeers) "Not enough peers" else ""),
          (if (!enoughTips) "Not enough tips" else "")
        ).filter(_.nonEmpty).mkString(", ")
        Logger[F].debug(s"Cannot start own consensus: ${reason}")
      } else Applicative[F].unit
    } yield res

  def isPeerInputValid[F[_]: Async: KryoSerializer: SecurityProvider](
    input: Signed[PeerBlockConsensusInput]
  ): F[Boolean] =
    for {
      hasValidSignature <- input.hasValidSignature
      isSignedBy = input.isSignedBy(
        Set(PeerId._Id.get(input.value.senderId))
      )
    } yield hasValidSignature && isSignedBy
}
