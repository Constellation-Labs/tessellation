package io.constellationnetwork.currency.l0.snapshot.services

import cats.effect.Async
import cats.syntax.applicative._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.DataCalculatedState
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.transaction.{RewardTransaction, Transaction}
import io.constellationnetwork.security.signature.Signed

object NoopRewards {
  def make[F[_]: Async]: Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent] =
    (
      _: Signed[CurrencyIncrementalSnapshot],
      _: SortedMap[Address, Balance],
      _: SortedSet[Signed[Transaction]],
      _: trigger.ConsensusTrigger,
      _: Set[CurrencySnapshotEvent],
      _: Option[DataCalculatedState]
    ) => SortedSet.empty[RewardTransaction].pure[F]
}
