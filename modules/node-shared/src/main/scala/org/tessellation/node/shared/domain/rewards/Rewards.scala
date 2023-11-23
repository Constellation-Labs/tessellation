package org.tessellation.node.shared.domain.rewards

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.currency.dataApplication.DataCalculatedState
import org.tessellation.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.snapshot.{IncrementalSnapshot, StateProof}
import org.tessellation.schema.transaction.{RewardTransaction, Transaction}
import org.tessellation.security.signature.Signed

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
