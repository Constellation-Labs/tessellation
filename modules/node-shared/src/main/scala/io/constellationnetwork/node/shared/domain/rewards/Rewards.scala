package io.constellationnetwork.node.shared.domain.rewards

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.DataCalculatedState
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.snapshot.{IncrementalSnapshot, StateProof}
import io.constellationnetwork.schema.transaction.{RewardTransaction, Transaction}
import io.constellationnetwork.security.signature.Signed

trait Rewards[F[_], P <: StateProof, S <: IncrementalSnapshot[P], E] {
  def distribute(
    lastArtifact: Signed[S],
    lastBalances: SortedMap[Address, Balance],
    acceptedTransactions: SortedSet[Signed[Transaction]],
    trigger: ConsensusTrigger,
    events: Set[E],
    maybeCalculatedState: Option[DataCalculatedState] = None
  ): F[SortedSet[RewardTransaction]]
}
