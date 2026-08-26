package io.constellationnetwork.currency.l0.snapshot.synchronous

import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorHandler

class Consensus[F[_], Event, Key, Artifact, Context, Status, Outcome, Kind](
  val handler: RumorHandler[F],
  val storage: ConsensusStorage[F, Event, Key, Artifact, Context, Status, Outcome, Kind],
  val manager: ConsensusManager[F, Key, Artifact, Context, Status, Outcome, Kind],
  val routes: ConsensusRoutes[F, Key, Artifact, Context, Status, Outcome, Kind],
  val consensusFns: ConsensusFunctions[F, Event, Key, Artifact, Context],
  val triggerEventConsensus: Option[F[Unit]] = None
)
