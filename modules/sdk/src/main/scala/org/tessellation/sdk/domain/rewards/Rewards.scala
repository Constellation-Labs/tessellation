package org.tessellation.sdk.domain.rewards

import cats.data.NonEmptySet

import scala.collection.immutable.SortedSet

import org.tessellation.schema.CurrencyData
import org.tessellation.schema.ID.Id
import org.tessellation.schema.transaction.{RewardTransaction, Transaction}
import org.tessellation.security.signature.Signed

trait Rewards[F[_]] {
  def distribute(
    lastSnapshot: CurrencyData,
    lastSigners: NonEmptySet[Id],
    acceptedTransactions: SortedSet[Signed[Transaction]],
    mint: Boolean
  ): F[SortedSet[RewardTransaction]]
}
