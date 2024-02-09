package org.tessellation.node.shared.infrastructure.consensus

import org.tessellation.node.shared.domain.consensus.ConsensusFunctions
import org.tessellation.node.shared.infrastructure.gossip.RumorHandler

class Consensus[F[_], Event, Key, Artifact, Context, Status, Outcome, Kind](
  val handler: RumorHandler[F],
  val storage: ConsensusStorage[F, Event, Key, Artifact, Context, Status, Outcome, Kind],
  val manager: ConsensusManager[F, Key, Artifact, Context, Status, Outcome, Kind],
  val routes: ConsensusRoutes[F, Key, Artifact, Context, Status, Outcome, Kind],
  val consensusFns: ConsensusFunctions[F, Event, Key, Artifact, Context]
)
