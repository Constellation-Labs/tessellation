package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.Async
import cats.effect.kernel.Clock
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

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
  updatedAt: FiniteDuration
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
      time
    )
  } yield consensusResources
}
