package io.constellationnetwork.node.shared.logger

import io.constellationnetwork.schema.peer.PeerId

/** Specialized logger for consensus round events.
  */
trait ConsensusLogger[F[_]] {
  def collectingFacilities(facilitators: List[PeerId]): F[Unit]
  def collectingProposals(facilitators: List[PeerId]): F[Unit]
  def collectingSignatures(facilitators: List[PeerId]): F[Unit]
  def roundFinished(facilitators: List[PeerId]): F[Unit]
}
