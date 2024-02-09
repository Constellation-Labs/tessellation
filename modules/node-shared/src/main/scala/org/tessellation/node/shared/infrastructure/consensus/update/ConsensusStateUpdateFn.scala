package org.tessellation.node.shared.infrastructure.consensus.update

import cats.data.StateT

import org.tessellation.node.shared.infrastructure.consensus.{ConsensusResources, ConsensusState}

trait ConsensusStateUpdateFn[F[_], Key, Artifact, Status, Outcome, Kind, Action]
    extends (ConsensusResources[Artifact, Kind] => StateT[F, ConsensusState[Key, Status, Outcome, Kind], Action])
