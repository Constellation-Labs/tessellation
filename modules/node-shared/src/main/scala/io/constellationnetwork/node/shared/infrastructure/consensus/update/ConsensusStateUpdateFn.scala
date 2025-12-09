package io.constellationnetwork.node.shared.infrastructure.consensus.update

import cats.data.StateT

import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusResources
import io.constellationnetwork.node.shared.infrastructure.consensus.state._

trait ConsensusStateUpdateFn[F[_], Key, Artifact, Status, Outcome, Kind, Action]
    extends (ConsensusResources[Artifact, Kind] => StateT[F, ConsensusState[Key, Status, Outcome, Kind], Action])
