package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.Async
import cats.effect.kernel.Clock
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{ProposalQC, ViewChangeVote}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive

/** Represents various data collected from other peers
  */
@derive(eqv, show)
case class ConsensusResources[A, Kind](
  peerDeclarationsMap: Map[PeerId, PeerDeclarations],
  acksMap: Map[(PeerId, Kind), Set[PeerId]],
  withdrawalsMap: Map[PeerId, Kind],
  ackKinds: Set[Kind],
  artifacts: Map[Hash, A],
  updatedAt: FiniteDuration,
  viewChangeVotes: Map[(Long, Long), Map[PeerId, Signed[ViewChangeVote]]] = Map.empty,
  proposalQcs: Map[(Long, Hash), ProposalQC] = Map.empty
)

object ConsensusResources {
  def empty[F[_]: Async, A, Kind]: F[ConsensusResources[A, Kind]] = for {
    time <- Clock[F].monotonic
    consensusResources = ConsensusResources(
      Map.empty[PeerId, PeerDeclarations],
      Map.empty[(PeerId, Kind), Set[PeerId]],
      Map.empty[PeerId, Kind],
      Set.empty[Kind],
      Map.empty[Hash, A],
      time,
      Map.empty[(Long, Long), Map[PeerId, Signed[ViewChangeVote]]],
      Map.empty[(Long, Hash), ProposalQC]
    )
  } yield consensusResources
}
