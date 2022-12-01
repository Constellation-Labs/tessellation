package org.tessellation.sdk.infrastructure.consensus.update

import cats.data.StateT

import org.tessellation.sdk.infrastructure.consensus.{ConsensusResources, ConsensusState}

trait ConsensusStateUpdateFn[F[_], Key, Artifact, Action]
    extends (ConsensusResources[Artifact] => StateT[F, ConsensusState[Key, Artifact], Action])
