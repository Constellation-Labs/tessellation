package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.SpendTransaction
import io.constellationnetwork.schema.balance.{Amount, Balance, BalanceArithmeticError}
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.security.Hashed

import eu.timepit.refined.types.numeric.NonNegLong

trait SpendTransactionBalanceManager[F[_]] {
  def updateGlobalBalancesBySpendTransactions(
    currentBalances: SortedMap[Address, Balance],
    allGlobalAllowSpends: SortedMap[Address, List[Hashed[AllowSpend]]],
    globalSpendTransactions: List[SpendTransaction],
    consumeSettledAllowSpends: Boolean
  ): Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedMap[Address, Balance])]
}

object SpendTransactionBalanceManager {

  def make[F[_]](): SpendTransactionBalanceManager[F] = new SpendTransactionBalanceManager[F] {

    def updateGlobalBalancesBySpendTransactions(
      currentBalances: SortedMap[Address, Balance],
      allGlobalAllowSpends: SortedMap[Address, List[Hashed[AllowSpend]]],
      globalSpendTransactions: List[SpendTransaction],
      consumeSettledAllowSpends: Boolean
    ): Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedMap[Address, Balance])] =
      globalSpendTransactions
        .foldLeft[
          Either[
            BalanceArithmeticError,
            (SortedMap[Address, Balance], SortedMap[Address, Balance], SortedMap[Address, List[Hashed[AllowSpend]]])
          ]
        ](Right((currentBalances, SortedMap.empty[Address, Balance], allGlobalAllowSpends))) { (innerAccEither, spendTransaction) =>
          for {
            (balances, balancesDelta, availableAllowSpends) <- innerAccEither
            destinationAddress = spendTransaction.destination
            sourceAddress = spendTransaction.source

            addressAllowSpends = availableAllowSpends.getOrElse(sourceAddress, List.empty)
            spendTransactionAmount = SwapAmount.toAmount(spendTransaction.amount)
            currentDestinationBalance = balances.getOrElse(destinationAddress, Balance.empty)

            updatedBalances <- spendTransaction.allowSpendRef.flatMap { allowSpendRef =>
              addressAllowSpends.find(_.hash === allowSpendRef)
            } match {
              case Some(allowSpend) =>
                val sourceAllowSpendAddress = allowSpend.source
                val currentSourceBalance = balances.getOrElse(sourceAllowSpendAddress, Balance.empty)
                val balanceToReturnToAddress = allowSpend.amount.value.value - spendTransactionAmount.value.value
                val remainingAllowSpends =
                  if (consumeSettledAllowSpends)
                    availableAllowSpends.updated(sourceAddress, addressAllowSpends.filterNot(_.hash === allowSpend.hash))
                  else
                    availableAllowSpends

                for {
                  updatedDestinationBalance <- currentDestinationBalance.plus(spendTransactionAmount)
                  updatedSourceBalance <- currentSourceBalance.plus(
                    Amount(NonNegLong.from(balanceToReturnToAddress).getOrElse(NonNegLong.MinValue))
                  )
                  balancesUpdated = balances
                    .updated(destinationAddress, updatedDestinationBalance)
                    .updated(sourceAllowSpendAddress, updatedSourceBalance)
                  balancesDeltaUpdated = balancesDelta
                    .updated(destinationAddress, updatedDestinationBalance)
                    .updated(sourceAllowSpendAddress, updatedSourceBalance)
                } yield (balancesUpdated, balancesDeltaUpdated, remainingAllowSpends)

              case None =>
                val currentSourceBalance = balances.getOrElse(sourceAddress, Balance.empty)

                for {
                  updatedDestinationBalance <- currentDestinationBalance.plus(spendTransactionAmount)
                  updatedSourceBalance <- currentSourceBalance.minus(spendTransactionAmount)
                  balancesUpdated = balances
                    .updated(destinationAddress, updatedDestinationBalance)
                    .updated(sourceAddress, updatedSourceBalance)
                  balancesDeltaUpdated = balancesDelta
                    .updated(destinationAddress, updatedDestinationBalance)
                    .updated(sourceAddress, updatedSourceBalance)
                } yield (balancesUpdated, balancesDeltaUpdated, availableAllowSpends)
            }
          } yield updatedBalances
        }
        .map { case (balances, balancesDelta, _) => (balances, balancesDelta) }
  }
}
