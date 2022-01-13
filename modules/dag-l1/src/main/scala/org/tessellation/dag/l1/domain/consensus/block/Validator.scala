package org.tessellation.dag.l1.domain.consensus.block

import cats.Monad
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

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

object Validator {

  def isReadyForBlockConsensus(state: NodeState): Boolean = state == Ready

  private def enoughPeersForConsensus[F[_]: Monad](
    clusterStorage: ClusterStorage[F],
    peersCount: PosInt
  ): F[Boolean] =
    clusterStorage.getPeers
      .map(_.filter(p => isReadyForBlockConsensus(p.state)))
      .map(_.size >= peersCount)

  def canStartOwnConsensus[F[_]: Monad](
    consensusStorage: ConsensusStorage[F],
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    peersCount: PosInt
  ): F[Boolean] =
    for {
      noOwnRoundInProgress <- consensusStorage.ownConsensus.get.map(_.isEmpty)
      stateReadyForConsensus <- nodeStorage.getNodeState.map(isReadyForBlockConsensus)
      enoughPeers <- enoughPeersForConsensus(clusterStorage, peersCount)
    } yield noOwnRoundInProgress && stateReadyForConsensus && enoughPeers

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
