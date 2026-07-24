package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{BurnTransaction, SpendTransaction}
import io.constellationnetwork.schema.balance.{Amount, Balance, BalanceArithmeticError}
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.Hash

import eu.timepit.refined.types.numeric.NonNegLong

trait SpendTransactionBalanceManager[F[_]] {
  def updateGlobalBalancesBySpendTransactions(
    currentBalances: SortedMap[Address, Balance],
    allGlobalAllowSpends: SortedMap[Address, List[Hashed[AllowSpend]]],
    globalSpendTransactions: List[SpendTransaction]
  ): Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedMap[Address, Balance])]

  def updateGlobalBalancesByBurnTransactions(
    currentBalances: SortedMap[Address, Balance],
    allGlobalAllowSpends: SortedMap[Address, List[Hashed[AllowSpend]]],
    globalBurnTransactions: List[BurnTransaction]
  ): Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedMap[Address, Balance])]
}

object SpendTransactionBalanceManager {

  def make[F[_]](): SpendTransactionBalanceManager[F] = new SpendTransactionBalanceManager[F] {

    def updateGlobalBalancesBySpendTransactions(
      currentBalances: SortedMap[Address, Balance],
      allGlobalAllowSpends: SortedMap[Address, List[Hashed[AllowSpend]]],
      globalSpendTransactions: List[SpendTransaction]
    ): Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedMap[Address, Balance])] =
      globalSpendTransactions.foldLeft[Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedMap[Address, Balance])]](
        Right((currentBalances, SortedMap.empty[Address, Balance]))
      ) { (innerAccEither, spendTransaction) =>
        for {
          (balances, balancesDelta) <- innerAccEither
          destinationAddress = spendTransaction.destination
          sourceAddress = spendTransaction.source

          addressAllowSpends = allGlobalAllowSpends.getOrElse(sourceAddress, List.empty)
          spendTransactionAmount = SwapAmount.toAmount(spendTransaction.amount)
          currentDestinationBalance = balances.getOrElse(destinationAddress, Balance.empty)

          updatedBalances <- spendTransaction.allowSpendRef.flatMap { allowSpendRef =>
            addressAllowSpends.find(_.hash === allowSpendRef)
          } match {
            case Some(allowSpend) =>
              val sourceAllowSpendAddress = allowSpend.source
              val currentSourceBalance = balances.getOrElse(sourceAllowSpendAddress, Balance.empty)
              val balanceToReturnToAddress = allowSpend.amount.value.value - spendTransactionAmount.value.value

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
              } yield (balancesUpdated, balancesDeltaUpdated)

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
              } yield (balancesUpdated, balancesDeltaUpdated)
          }
        } yield updatedBalances
      }

    /** Burn semantics = spend semantics MINUS the destination credit (see [[updateGlobalBalancesBySpendTransactions]]). Every branch
      * strictly reduces totalSupply by the burned amount; no destination is ever credited.
      */
    def updateGlobalBalancesByBurnTransactions(
      currentBalances: SortedMap[Address, Balance],
      allGlobalAllowSpends: SortedMap[Address, List[Hashed[AllowSpend]]],
      globalBurnTransactions: List[BurnTransaction]
    ): Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedMap[Address, Balance])] =
      globalBurnTransactions.foldLeft[Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedMap[Address, Balance])]](
        Right((currentBalances, SortedMap.empty[Address, Balance]))
      ) { (innerAccEither, burnTransaction) =>
        for {
          (balances, balancesDelta) <- innerAccEither
          sourceAddress = burnTransaction.source

          addressAllowSpends = allGlobalAllowSpends.getOrElse(sourceAddress, List.empty)
          burnTransactionAmount = SwapAmount.toAmount(burnTransaction.amount)

          updatedBalances <- burnTransaction.allowSpendRef.flatMap { allowSpendRef =>
            addressAllowSpends.find(_.hash === allowSpendRef)
          } match {
            case Some(allowSpend) =>
              val sourceAllowSpendAddress = allowSpend.source
              val currentSourceBalance = balances.getOrElse(sourceAllowSpendAddress, Balance.empty)
              val balanceToReturnToAddress = allowSpend.amount.value.value - burnTransactionAmount.value.value

              for {
                updatedSourceBalance <- currentSourceBalance.plus(
                  Amount(NonNegLong.from(balanceToReturnToAddress).getOrElse(NonNegLong.MinValue))
                )
                balancesUpdated = balances.updated(sourceAllowSpendAddress, updatedSourceBalance)
                balancesDeltaUpdated = balancesDelta.updated(sourceAllowSpendAddress, updatedSourceBalance)
              } yield (balancesUpdated, balancesDeltaUpdated)

            case None =>
              val currentSourceBalance = balances.getOrElse(sourceAddress, Balance.empty)

              for {
                updatedSourceBalance <- currentSourceBalance.minus(burnTransactionAmount)
                balancesUpdated = balances.updated(sourceAddress, updatedSourceBalance)
                balancesDeltaUpdated = balancesDelta.updated(sourceAddress, updatedSourceBalance)
              } yield (balancesUpdated, balancesDeltaUpdated)
          }
        } yield updatedBalances
      }
  }
}
