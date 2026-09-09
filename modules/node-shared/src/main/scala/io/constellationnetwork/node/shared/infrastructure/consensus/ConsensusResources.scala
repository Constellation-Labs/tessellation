package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.Async
import cats.effect.kernel.Clock
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus.{CertifiedProposalQC, OutcomeVote}
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
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
  timeoutVotes: Map[(Long, Long), Map[PeerId, Signed[TimeoutVote]]] = Map.empty,
  timeoutCertificates: Map[(Long, Long), TimeoutCertificate] = Map.empty,
  proposalQcs: Map[(Long, Hash), ProposalQC] = Map.empty,
  // EvictionVotes collected for the current round. Outer key = target peer (who the
  // cluster is considering evicting), inner key = voter (who cast the vote). Round-scoped
  // (not view-scoped), preserved across abandonment retries so eviction progress is not
  // lost when a round loops on stall cycles.
  evictionVotes: Map[PeerId, Map[PeerId, Signed[EvictionVote]]] = Map.empty,
  // AdmissionVotes collected for the current round (B2). Same shape as evictionVotes:
  // outer key = target peer (previously-removed peer now being re-admitted), inner key
  // = voter (a current facilitator who observes the target at tip). Round-scoped,
  // preserved across abandonment retries.
  admissionVotes: Map[PeerId, Map[PeerId, Signed[AdmissionVote]]] = Map.empty,
  // V35 prepare votes are indexed by (certified view, complete ProposalValue hash), so equivocations remain visible rather than one value
  // overwriting another. Each inner map is first-write-wins per authenticated rumor origin.
  outcomeVotes: Map[(Long, Hash), Map[PeerId, OutcomeVote]] = Map.empty,
  certifiedProposalQcs: Map[(Long, Hash), CertifiedProposalQC] = Map.empty
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
      Map.empty[(Long, Long), Map[PeerId, Signed[TimeoutVote]]],
      Map.empty[(Long, Long), TimeoutCertificate],
      Map.empty[(Long, Hash), ProposalQC],
      Map.empty[PeerId, Map[PeerId, Signed[EvictionVote]]]
    )
  } yield consensusResources
}
