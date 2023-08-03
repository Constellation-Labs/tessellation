package org.tessellation.sdk.domain.rewards

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.snapshot.{IncrementalSnapshot, StateProof}
import org.tessellation.schema.transaction.{RewardTransaction, Transaction}
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.security.signature.Signed

trait Rewards[F[_], P <: StateProof, S <: IncrementalSnapshot[P]] {
  def distribute(
    lastArtifact: Signed[S],
    lastBalances: SortedMap[Address, Balance],
    acceptedTransactions: SortedSet[Signed[Transaction]],
    trigger: ConsensusTrigger
  ): F[SortedSet[RewardTransaction]]
}
