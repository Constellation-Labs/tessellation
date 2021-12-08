package org.tessellation.dag.l1.storage

import cats.effect.{Ref, Sync}
import cats.syntax.functor._
import cats.syntax.option._

import org.tessellation.dag.l1.DAGStateChannel.RoundId
import org.tessellation.dag.l1.storage.BlockStorage.PulledTips
import org.tessellation.dag.l1.{CancellationReason, DAGBlock, Proposal}
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.security.signature.Signed

import io.chrisdavenport.mapref.MapRef

case class ConsensusStorage[F[_]](
  ownConsensus: Ref[F, Option[RoundData]],
  peerConsensuses: MapRef[F, RoundId, Option[RoundData]]
)

object ConsensusStorage {

  def make[F[_]: Sync]: F[ConsensusStorage[F]] =
    for {
      peerConsensuses <- MapRef.ofConcurrentHashMap[F, RoundId, RoundData]()
      ownConsensus = Ref.unsafe(none[RoundData])
    } yield ConsensusStorage(ownConsensus, peerConsensuses)
}

case class RoundData(
  roundId: RoundId,
  peers: Set[Peer],
  owner: PeerId,
  ownProposal: Proposal,
  ownBlock: Option[Signed[DAGBlock]],
  ownCancellation: Option[CancellationReason],
  peerProposals: Map[PeerId, Proposal],
  peerBlocks: Map[PeerId, Signed[DAGBlock]],
  peerCancellations: Map[PeerId, CancellationReason],
  tips: PulledTips
)
