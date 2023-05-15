package org.tessellation.currency.l0.snapshot.services

import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.applicative._

import scala.collection.immutable.SortedSet

import org.tessellation.schema.CurrencyData
import org.tessellation.schema.ID.Id
import org.tessellation.schema.transaction.{RewardTransaction, Transaction}
import org.tessellation.sdk.domain.rewards.Rewards
import org.tessellation.security.signature.Signed

object Rewards {
  def make[F[_]: Async]: Rewards[F] =
    (
      _: CurrencyData,
      _: NonEmptySet[Id],
      _: SortedSet[Signed[Transaction]],
      _: Boolean
    ) => SortedSet.empty[RewardTransaction].pure[F]
}
