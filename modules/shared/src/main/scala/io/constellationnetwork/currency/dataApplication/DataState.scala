package io.constellationnetwork.currency.dataApplication

import scala.collection.immutable.SortedSet

import io.constellationnetwork.schema.artifact.SharedArtifact

trait DataOnChainState
trait DataCalculatedState

case class DataState[A <: DataOnChainState, B <: DataCalculatedState](
  onChain: A,
  calculated: B,
  sharedArtifacts: SortedSet[SharedArtifact] = SortedSet.empty[SharedArtifact]
) {
  def asBase: DataState[DataOnChainState, DataCalculatedState] =
    DataState(onChain, calculated, sharedArtifacts)
}

object DataState {
  type Base = DataState[DataOnChainState, DataCalculatedState]
}
