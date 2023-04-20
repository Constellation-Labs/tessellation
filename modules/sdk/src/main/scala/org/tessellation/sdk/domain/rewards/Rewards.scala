package org.tessellation.sdk.domain.rewards

import scala.collection.immutable.SortedSet

import org.tessellation.schema.Block
import org.tessellation.schema.snapshot.{IncrementalSnapshot, StateProof}
import org.tessellation.schema.transaction.{RewardTransaction, Transaction}
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.security.signature.Signed

trait Rewards[F[_], T <: Transaction, B <: Block[T], P <: StateProof, S <: IncrementalSnapshot[T, B, P]] {
  def distribute(lastArtifact: Signed[S], transactions: SortedSet[Signed[T]], trigger: ConsensusTrigger): F[SortedSet[RewardTransaction]]
}
