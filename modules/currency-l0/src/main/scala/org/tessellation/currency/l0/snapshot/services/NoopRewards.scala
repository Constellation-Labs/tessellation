package org.tessellation.currency.l0.snapshot.services

import cats.effect.Async
import cats.syntax.applicative._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.currency.schema.currency._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.transaction.RewardTransaction
import org.tessellation.sdk.domain.rewards.Rewards
import org.tessellation.sdk.infrastructure.consensus.trigger
import org.tessellation.security.signature.Signed

object NoopRewards {
  def make[F[_]: Async]: Rewards[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot] =
    (
      _: Signed[CurrencyIncrementalSnapshot],
      _: SortedMap[Address, Balance],
      _: SortedSet[Signed[CurrencyTransaction]],
      _: trigger.ConsensusTrigger
    ) => SortedSet.empty[RewardTransaction].pure[F]
}
