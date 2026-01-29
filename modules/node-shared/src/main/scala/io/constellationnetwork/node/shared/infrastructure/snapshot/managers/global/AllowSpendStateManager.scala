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

import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Result of allow spend acceptance containing full state and deltas */
case class AllowSpendAcceptanceResult(
  fullState: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
  deltas: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
)

trait AllowSpendStateManager[F[_]] {
  def acceptAllowSpends(
    epochProgress: EpochProgress,
    activeAllowSpendsFromCurrencySnapshots: SortedMap[Address, SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
    globalAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
    lastActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
    allAcceptedSpendTxns: List[SpendTransaction]
  )(implicit hasher: Hasher[F]): F[AllowSpendAcceptanceResult]

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
  ): Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedMap[Address, Balance])]
}

object AllowSpendStateManager {

  def make[F[_]: Async](): AllowSpendStateManager[F] = new AllowSpendStateManager[F] {
    private val logger = Slf4jLogger.getLoggerFromName[F]("AllowSpendStateManager")

    def acceptAllowSpends(
      epochProgress: EpochProgress,
      activeAllowSpendsFromCurrencySnapshots: SortedMap[Address, SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      globalAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      lastActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      allAcceptedSpendTxns: List[SpendTransaction]
    )(implicit hasher: Hasher[F]): F[AllowSpendAcceptanceResult] = {
      val allAcceptedSpendTxnsAllowSpendsRefs =
        allAcceptedSpendTxns
          .flatMap(_.allowSpendRef)

      val lastActiveGlobalAllowSpends = lastActiveAllowSpends.getOrElse(None, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
      val expiredGlobalAllowSpends = filterExpiredAllowSpends(lastActiveGlobalAllowSpends, epochProgress)

      val totalLastActiveCount = lastActiveGlobalAllowSpends.values.map(_.size).sum
      val totalNewCount = globalAllowSpends.values.map(_.size).sum
      val totalExpiredCount = expiredGlobalAllowSpends.values.map(_.size).sum

      val unexpiredGlobalAllowSpends = (globalAllowSpends |+| expiredGlobalAllowSpends).foldLeft(lastActiveGlobalAllowSpends) {
        case (acc, (address, allowSpends)) =>
          val lastAddressAllowSpends = acc.getOrElse(address, SortedSet.empty[Signed[AllowSpend]])
          val unexpired = (lastAddressAllowSpends ++ allowSpends).filter(_.lastValidEpochProgress >= epochProgress)
          acc + (address -> unexpired)
      }

      val unexpiredGlobalWithoutSpendTransactionsF =
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
        accAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
        accDeltas: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
      ): F[
        (
          SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
          SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
        )
      ] = {
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

            unexpiredWithoutSpendTransactions.map { validAllowSpends =>
              val hasChanged = lastAddressAllowSpends != validAllowSpends
              (address, validAllowSpends, hasChanged)
            }
        }.map { updatedMetagraphAllowSpends =>
          val fullStateMap = SortedMap(updatedMetagraphAllowSpends.map { case (addr, spends, _) => addr -> spends }: _*)
          val deltasMap = SortedMap(updatedMetagraphAllowSpends.collect {
            case (addr, spends, true) => addr -> spends
          }: _*)

          val updatedFullState = accAllowSpends + (metagraphId.some -> fullStateMap)
          val updatedDeltas = if (deltasMap.nonEmpty) {
            accDeltas + (metagraphId.some -> deltasMap)
          } else {
            accDeltas
          }

          (updatedFullState, updatedDeltas)
        }
      }

      // Process metagraph allow spends and track deltas
      val processedMetagraphsF = activeAllowSpendsFromCurrencySnapshots.toList
        .foldLeft((lastActiveAllowSpends, SortedMap.empty[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]).pure[F]) {
          case (accF, (metagraphId, metagraphAllowSpends)) =>
            for {
              (accFullState, accDeltas) <- accF
              (updatedFullState, updatedDeltas) <- processMetagraphAllowSpends(metagraphId, metagraphAllowSpends, accFullState, accDeltas)
            } yield (updatedFullState, updatedDeltas)
        }

      for {
        _ <- logger.info(
          s"[AllowSpendAccept] epochProgress=${epochProgress.value.value} " +
            s"lastActiveGlobal=$totalLastActiveCount newGlobal=$totalNewCount expired=$totalExpiredCount " +
            s"currencySnapshots=${activeAllowSpendsFromCurrencySnapshots.size} spendTxns=${allAcceptedSpendTxns.size}"
        )
        (updatedCurrencyAllowSpends, currencyDeltas) <- processedMetagraphsF
        validGlobalAllowSpends <- unexpiredGlobalWithoutSpendTransactionsF
        validGlobalCount = validGlobalAllowSpends.values.map(_.size).sum
        _ <- logger.info(
          s"[AllowSpendAccept] validGlobalAllowSpends=$validGlobalCount " +
            s"addresses=${validGlobalAllowSpends.keys.map(_.value).mkString(",")}"
        )

        globalDeltas: SortedMap[Address, SortedSet[Signed[AllowSpend]]] =
          validGlobalAllowSpends.filter {
            case (address, allowSpends) =>
              !lastActiveGlobalAllowSpends.get(address).contains(allowSpends)
          }

        fullState =
          if (validGlobalAllowSpends.nonEmpty) {
            updatedCurrencyAllowSpends + (None -> validGlobalAllowSpends)
          } else {
            updatedCurrencyAllowSpends
          }

        deltas =
          if (globalDeltas.nonEmpty) {
            currencyDeltas + (None -> globalDeltas)
          } else {
            currencyDeltas
          }

        totalFullStateCount = fullState.values.flatMap(_.values).map(_.size).sum
        totalDeltasCount = deltas.values.flatMap(_.values).map(_.size).sum
        _ <- logger.info(s"[AllowSpendAccept] result: fullState=$totalFullStateCount deltas=$totalDeltasCount")

      } yield AllowSpendAcceptanceResult(fullState, deltas)
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
    ): Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedMap[Address, Balance])] = {
      val lastActiveGlobalAllowSpends = lastActiveAllowSpends.getOrElse(None, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
      val expiredGlobalAllowSpends = filterExpiredAllowSpends(lastActiveGlobalAllowSpends, epochProgress)

      val result = (globalAllowSpends |+| expiredGlobalAllowSpends)
        .foldLeft[Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedMap[Address, Balance])]](
          Right((currentBalances, SortedMap.empty[Address, Balance]))
        ) {
          case (accEither, (address, allowSpends)) =>
            for {
              (balances, balancesDelta) <- accEither
              initialBalance = balances.getOrElse(address, Balance.empty)

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

              updatedAcc = balances.updated(address, expiredBalance)
              updatedBalancesDelta = balancesDelta.updated(address, expiredBalance)
            } yield (updatedAcc, updatedBalancesDelta)
        }
      result
    }
  }
}
