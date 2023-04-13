package org.tessellation.sdk.domain.rewards

import scala.collection.immutable.SortedSet

import org.tessellation.schema.snapshot.IncrementalSnapshot
import org.tessellation.schema.transaction.RewardTransaction
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.security.signature.Signed

trait Rewards[F[_], S <: IncrementalSnapshot[_, _, _]] {
  def distribute(artifact: Signed[S], trigger: ConsensusTrigger): F[SortedSet[RewardTransaction]]
}
