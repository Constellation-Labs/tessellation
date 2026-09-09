package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{AllowSpendExpiration, SharedArtifact, SpendTransaction}
import io.constellationnetwork.schema.balance.{Amount, Balance, BalanceArithmeticError}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.syntax.sortedCollection.sortedSetSyntax

import eu.timepit.refined.types.all.NonNegLong

class AllowSpendOpsManager[F[_]: Async] {

  def acceptCurrencyAllowSpends(
    epochProgress: EpochProgress,
    incomingCurrencyAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
    existentCurrencyAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
    allAcceptedSpendTxns: List[SpendTransaction],
    lastUnsyncGlobalSnapshotOrdinal: SnapshotOrdinal,
    fixingAllowSpendExpiration: SnapshotOrdinal
  )(implicit hasher: Hasher[F]): F[SortedMap[Address, SortedSet[Signed[AllowSpend]]]] = {
    val allAcceptedSpendTxnsAllowSpendsRefs =
      allAcceptedSpendTxns
        .flatMap(_.allowSpendRef)

    for {
      expiredAllowSpends <- filterExpiredAllowSpends(
        existentCurrencyAllowSpends,
        epochProgress,
        allAcceptedSpendTxns,
        lastUnsyncGlobalSnapshotOrdinal,
        fixingAllowSpendExpiration
      )

      unexpiredAllowSpends = (incomingCurrencyAllowSpends |+| expiredAllowSpends).foldLeft(existentCurrencyAllowSpends) {
        case (acc, (address, allowSpends)) =>
          val lastAddressAllowSpends = acc.getOrElse(address, SortedSet.empty[Signed[AllowSpend]])
          val unexpired = (lastAddressAllowSpends ++ allowSpends).filter(_.value.lastValidEpochProgress >= epochProgress)
          acc + (address -> unexpired)
      }

      result <- unexpiredAllowSpends.toList.foldLeftM(unexpiredAllowSpends) {
        case (acc, (address, allowSpends)) =>
          allowSpends.toList.traverse(_.toHashed).map { hashedAllowSpends =>
            val validAllowSpends = hashedAllowSpends
              .filterNot(h => allAcceptedSpendTxnsAllowSpendsRefs.contains(h.hash))
              .map(_.signed)
              .to(SortedSet)

            acc + (address -> validAllowSpends)
          }
      }
    } yield result
  }

  def filterExpiredAllowSpends(
    activeCurrencyAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
    epochProgress: EpochProgress,
    metagraphIdSpendTransactions: List[SpendTransaction],
    lastUnsyncGlobalSnapshotOrdinal: SnapshotOrdinal,
    fixingAllowSpendExpiration: SnapshotOrdinal
  )(implicit hasher: Hasher[F]): F[SortedMap[Address, SortedSet[Signed[AllowSpend]]]] =
    if (lastUnsyncGlobalSnapshotOrdinal > fixingAllowSpendExpiration) {
      val spendTxnAllowSpendsRefsOpt = metagraphIdSpendTransactions.traverse(_.allowSpendRef)

      spendTxnAllowSpendsRefsOpt match {
        case Some(spendTxnAllowSpendsRefs) =>
          val spendTxnAllowSpendsRefsSet = spendTxnAllowSpendsRefs.toSet

          activeCurrencyAllowSpends.toList.traverse {
            case (address, allowSpends) =>
              allowSpends.toList.traverse { allowSpend =>
                allowSpend.toHashed.map { hashedAllowSpend =>
                  val isExpired = allowSpend.value.lastValidEpochProgress < epochProgress
                  val isNotUsed = !spendTxnAllowSpendsRefsSet.contains(hashedAllowSpend.hash)
                  if (isExpired && isNotUsed) Some(allowSpend) else None
                }
              }.map { expiredList =>
                val expiredForAddress = expiredList.flatten.to(SortedSet)
                if (expiredForAddress.nonEmpty) Some(address -> expiredForAddress)
                else None
              }
          }.map(_.flatten.to(SortedMap))

        case None =>
          SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]].pure[F]
      }
    } else {
      activeCurrencyAllowSpends.flatMap {
        case (address, allowSpends) =>
          val expired = allowSpends.filter(_.value.lastValidEpochProgress < epochProgress)
          if (expired.nonEmpty) Some(address -> expired)
          else None
      }
        .pure[F]
    }

  def updateCurrencyBalancesByAllowSpends(
    epochProgress: EpochProgress,
    currentBalances: SortedMap[Address, Balance],
    incomingCurrencyAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
    lastActiveCurrencyAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
    metagraphIdSpendTransactions: List[SpendTransaction],
    lastUnsyncGlobalSnapshotOrdinal: SnapshotOrdinal,
    fixingAllowSpendExpiration: SnapshotOrdinal
  )(implicit hasher: Hasher[F]): F[Either[BalanceArithmeticError, SortedMap[Address, Balance]]] =
    for {
      expiredCurrencyAllowSpends <- filterExpiredAllowSpends(
        lastActiveCurrencyAllowSpends,
        epochProgress,
        metagraphIdSpendTransactions,
        lastUnsyncGlobalSnapshotOrdinal,
        fixingAllowSpendExpiration
      )

      result = (incomingCurrencyAllowSpends |+| expiredCurrencyAllowSpends)
        .foldLeft[Either[BalanceArithmeticError, SortedMap[Address, Balance]]](Right(currentBalances)) {
          case (accEither, (address, allowSpends)) =>
            for {
              acc <- accEither
              initialBalance = acc.getOrElse(address, Balance.empty)
              unexpiredBalance <- {
                val unexpired = allowSpends.filter(_.value.lastValidEpochProgress >= epochProgress)

                unexpired.foldLeft[Either[BalanceArithmeticError, Balance]](Right(initialBalance)) {
                  (currentBalanceEither, signedAllowSpend) =>
                    val allowSpend = signedAllowSpend.value
                    for {
                      currentBalance <- currentBalanceEither
                      balanceAfterAmount <- currentBalance.minus(SwapAmount.toAmount(allowSpend.amount))
                      balanceAfterFee <- balanceAfterAmount.minus(AllowSpendFee.toAmount(allowSpend.fee))
                    } yield balanceAfterFee
                }
              }
              expiredBalance <- {
                val expired = allowSpends.filter(_.value.lastValidEpochProgress < epochProgress)
                expired.foldLeft[Either[BalanceArithmeticError, Balance]](Right(unexpiredBalance)) {
                  (currentBalanceEither, signedAllowSpend) =>
                    val allowSpend = signedAllowSpend.value
                    for {
                      currentBalance <- currentBalanceEither
                      balanceAfterExpiredAmount <- currentBalance.plus(SwapAmount.toAmount(allowSpend.amount))
                    } yield balanceAfterExpiredAmount
                }
              }
              updatedAcc = acc.updated(address, expiredBalance)
            } yield updatedAcc
        }
    } yield result

  def updateCurrencyBalancesBySpendTransactions(
    currentBalances: SortedMap[Address, Balance],
    allActiveCurrencyAllowSpends: SortedMap[Address, List[io.constellationnetwork.security.Hashed[AllowSpend]]],
    metagraphIdSpendTransactions: List[SpendTransaction],
    consumeSettledAllowSpends: Boolean
  ): Either[BalanceArithmeticError, SortedMap[Address, Balance]] =
    metagraphIdSpendTransactions
      .foldLeft[
        Either[
          BalanceArithmeticError,
          (SortedMap[Address, Balance], SortedMap[Address, List[io.constellationnetwork.security.Hashed[AllowSpend]]])
        ]
      ](Right((currentBalances, allActiveCurrencyAllowSpends))) { (txnAccEither, spendTransaction) =>
        for {
          (txnAcc, availableAllowSpends) <- txnAccEither
          destinationAddress = spendTransaction.destination
          sourceAddress = spendTransaction.source

          addressAllowSpends = availableAllowSpends.getOrElse(sourceAddress, List.empty)
          spendTransactionAmount = SwapAmount.toAmount(spendTransaction.amount)
          currentDestinationBalance = txnAcc.getOrElse(destinationAddress, Balance.empty)

          updatedBalances <- spendTransaction.allowSpendRef.flatMap { allowSpendRef =>
            addressAllowSpends.find(_.hash === allowSpendRef)
          } match {
            case Some(allowSpend) =>
              val sourceAllowSpendAddress = allowSpend.source
              val currentSourceBalance = txnAcc.getOrElse(sourceAllowSpendAddress, Balance.empty)
              val balanceToReturnToAddress = allowSpend.amount.value.value - spendTransactionAmount.value.value
              val remainingAllowSpends =
                if (consumeSettledAllowSpends)
                  availableAllowSpends.updated(sourceAddress, addressAllowSpends.filterNot(_.hash === allowSpend.hash))
                else
                  availableAllowSpends

              for {
                updatedDestinationBalance <- currentDestinationBalance.plus(spendTransactionAmount)
                updatedSourceBalance <- currentSourceBalance.plus(
                  Amount(
                    NonNegLong
                      .from(balanceToReturnToAddress)
                      .getOrElse(NonNegLong.MinValue)
                  )
                )
              } yield
                (
                  txnAcc
                    .updated(destinationAddress, updatedDestinationBalance)
                    .updated(sourceAllowSpendAddress, updatedSourceBalance),
                  remainingAllowSpends
                )

            case None =>
              val currentSourceBalance = txnAcc.getOrElse(sourceAddress, Balance.empty)

              for {
                updatedDestinationBalance <- currentDestinationBalance.plus(spendTransactionAmount)
                updatedSourceBalance <- currentSourceBalance.minus(spendTransactionAmount)
              } yield
                (
                  txnAcc
                    .updated(destinationAddress, updatedDestinationBalance)
                    .updated(sourceAddress, updatedSourceBalance),
                  availableAllowSpends
                )
          }
        } yield updatedBalances
      }
      .map(_._1)

  def emitAllowSpendsExpired(
    addressToSet: SortedMap[Address, SortedSet[Signed[AllowSpend]]]
  )(implicit hasher: Hasher[F]): F[SortedSet[SharedArtifact]] =
    addressToSet.values.flatten.toList
      .traverse(_.toHashed)
      .map(_.map(hashed => AllowSpendExpiration(hashed.hash): SharedArtifact).toSortedSet)
}

object AllowSpendOpsManager {
  def make[F[_]: Async]: AllowSpendOpsManager[F] =
    new AllowSpendOpsManager[F]
}
