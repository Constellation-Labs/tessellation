package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.SpendTransaction
import io.constellationnetwork.schema.balance.{Amount, Balance, BalanceArithmeticError}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.syntax.sortedCollection.sortedSetSyntax

trait AllowSpendStateManager[F[_]] {
  def acceptAllowSpends(
    epochProgress: EpochProgress,
    activeAllowSpendsFromCurrencySnapshots: SortedMap[Address, SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
    globalAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
    lastActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
    allAcceptedSpendTxns: List[SpendTransaction]
  )(implicit hasher: Hasher[F]): F[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]]

  def acceptAllowSpendRefs(
    lastAllowSpendRefs: SortedMap[Address, AllowSpendReference],
    lastAllowSpendContextUpdate: Map[Address, AllowSpendReference]
  ): SortedMap[Address, AllowSpendReference]

  def filterExpiredAllowSpends(
    allowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
    epochProgress: EpochProgress
  ): SortedMap[Address, SortedSet[Signed[AllowSpend]]]

  def updateGlobalBalancesByAllowSpends(
    epochProgress: EpochProgress,
    currentBalances: SortedMap[Address, Balance],
    globalAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
    lastActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
  ): Either[BalanceArithmeticError, SortedMap[Address, Balance]]
}

object AllowSpendStateManager {

  def make[F[_]: Async](): AllowSpendStateManager[F] = new AllowSpendStateManager[F] {

    def acceptAllowSpends(
      epochProgress: EpochProgress,
      activeAllowSpendsFromCurrencySnapshots: SortedMap[Address, SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      globalAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      lastActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      allAcceptedSpendTxns: List[SpendTransaction]
    )(implicit hasher: Hasher[F]): F[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]] = {
      val allAcceptedSpendTxnsAllowSpendsRefs =
        allAcceptedSpendTxns
          .flatMap(_.allowSpendRef)

      val lastActiveGlobalAllowSpends = lastActiveAllowSpends.getOrElse(None, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
      val expiredGlobalAllowSpends = filterExpiredAllowSpends(lastActiveGlobalAllowSpends, epochProgress)

      val unexpiredGlobalAllowSpends = (globalAllowSpends |+| expiredGlobalAllowSpends).foldLeft(lastActiveGlobalAllowSpends) {
        case (acc, (address, allowSpends)) =>
          val lastAddressAllowSpends = acc.getOrElse(address, SortedSet.empty[Signed[AllowSpend]])
          val unexpired = (lastAddressAllowSpends ++ allowSpends).filter(_.lastValidEpochProgress >= epochProgress)
          acc + (address -> unexpired)
      }

      val unexpiredGlobalWithoutSpendTransactions =
        unexpiredGlobalAllowSpends.toList.foldLeftM(unexpiredGlobalAllowSpends) {
          case (acc, (address, allowSpends)) =>
            allowSpends.toList.traverse(_.toHashed).map { hashedAllowSpends =>
              val validAllowSpends = hashedAllowSpends
                .filterNot(h => allAcceptedSpendTxnsAllowSpendsRefs.contains(h.hash))
                .map(_.signed)
                .to(SortedSet)

              acc + (address -> validAllowSpends)
            }
        }

      def processMetagraphAllowSpends(
        metagraphId: Address,
        metagraphAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
        accAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
      ): F[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]] = {
        val lastActiveMetagraphAllowSpends =
          accAllowSpends.getOrElse(metagraphId.some, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])

        metagraphAllowSpends.toList.traverse {
          case (address, addressAllowSpends) =>
            val lastAddressAllowSpends = lastActiveMetagraphAllowSpends.getOrElse(address, SortedSet.empty[Signed[AllowSpend]])

            val unexpired = (lastAddressAllowSpends ++ addressAllowSpends)
              .filter(_.lastValidEpochProgress >= epochProgress)

            val unexpiredWithoutSpendTransactions = unexpired.toList
              .traverse(_.toHashed)
              .map { hashedAllowSpends =>
                hashedAllowSpends.filterNot(h => allAcceptedSpendTxnsAllowSpendsRefs.contains(h.hash))
              }
              .map(_.map(_.signed).toSortedSet)

            unexpiredWithoutSpendTransactions.map(validAllowSpends => address -> validAllowSpends)
        }.map { updatedMetagraphAllowSpends =>
          accAllowSpends + (metagraphId.some -> SortedMap(updatedMetagraphAllowSpends: _*))
        }
      }

      activeAllowSpendsFromCurrencySnapshots.toList
        .foldLeft(lastActiveAllowSpends.pure[F]) {
          case (accAllowSpendsF, (metagraphId, metagraphAllowSpends)) =>
            for {
              accAllowSpends <- accAllowSpendsF
              updatedAllowSpends <- processMetagraphAllowSpends(metagraphId, metagraphAllowSpends, accAllowSpends)
            } yield updatedAllowSpends
        }
        .flatMap { updatedCurrencyAllowSpends =>
          unexpiredGlobalWithoutSpendTransactions.map { validGlobalAllowSpends =>
            if (validGlobalAllowSpends.nonEmpty)
              updatedCurrencyAllowSpends + (None -> validGlobalAllowSpends)
            else
              updatedCurrencyAllowSpends
          }
        }
    }

    def acceptAllowSpendRefs(
      lastAllowSpendRefs: SortedMap[Address, AllowSpendReference],
      lastAllowSpendContextUpdate: Map[Address, AllowSpendReference]
    ): SortedMap[Address, AllowSpendReference] =
      lastAllowSpendRefs ++ lastAllowSpendContextUpdate

    def filterExpiredAllowSpends(
      allowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      epochProgress: EpochProgress
    ): SortedMap[Address, SortedSet[Signed[AllowSpend]]] =
      allowSpends.view.mapValues(_.filter(_.lastValidEpochProgress < epochProgress)).to(SortedMap)

    def updateGlobalBalancesByAllowSpends(
      epochProgress: EpochProgress,
      currentBalances: SortedMap[Address, Balance],
      globalAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      lastActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
    ): Either[BalanceArithmeticError, SortedMap[Address, Balance]] = {
      val lastActiveGlobalAllowSpends = lastActiveAllowSpends.getOrElse(None, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
      val expiredGlobalAllowSpends = filterExpiredAllowSpends(lastActiveGlobalAllowSpends, epochProgress)

      val result = (globalAllowSpends |+| expiredGlobalAllowSpends).foldLeft[Either[BalanceArithmeticError, SortedMap[Address, Balance]]](
        Right(currentBalances)
      ) {
        case (accEither, (address, allowSpends)) =>
          for {
            acc <- accEither
            initialBalance = acc.getOrElse(address, Balance.empty)

            unexpiredBalance <- {
              val unexpired = allowSpends.filter(_.lastValidEpochProgress >= epochProgress)

              unexpired.foldLeft[Either[BalanceArithmeticError, Balance]](Right(initialBalance)) { (currentBalanceEither, allowSpend) =>
                for {
                  currentBalance <- currentBalanceEither
                  balanceAfterAmount <- currentBalance.minus(SwapAmount.toAmount(allowSpend.amount))
                  balanceAfterFee <- balanceAfterAmount.minus(AllowSpendFee.toAmount(allowSpend.fee))
                } yield balanceAfterFee
              }
            }

            expiredBalance <- {
              val expired = allowSpends.filter(_.lastValidEpochProgress < epochProgress)

              expired.foldLeft[Either[BalanceArithmeticError, Balance]](Right(unexpiredBalance)) { (currentBalanceEither, allowSpend) =>
                for {
                  currentBalance <- currentBalanceEither
                  balanceAfterExpiredAmount <- currentBalance.plus(SwapAmount.toAmount(allowSpend.amount))
                } yield balanceAfterExpiredAmount
              }
            }

            updatedAcc = acc.updated(address, expiredBalance)
          } yield updatedAcc
      }
      result
    }
  }
}
