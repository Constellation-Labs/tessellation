package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency.CurrencySnapshotInfoV1
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult.CurrencySnapshotWithState
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.TokenUnlock
import io.constellationnetwork.schema.balance.{Amount, Balance, BalanceArithmeticError}
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeError, MissingTokenLock, PendingDelegatedStakeWithdrawal}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.types.numeric.NonNegLong

trait TokenLockStateManager[F[_]] {
  def acceptTokenLocks(
    epochProgress: EpochProgress,
    acceptedGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
    lastActiveGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
    generatedTokenUnlocksByAddress: Map[Address, List[TokenUnlock]]
  )(implicit hasher: Hasher[F]): F[SortedMap[Address, SortedSet[Signed[TokenLock]]]]

  def acceptReplacementTokenLocks(
    acceptedTokenLocks: List[Signed[TokenLock]],
    lastSnapshotContext: GlobalSnapshotInfo
  )(implicit hasher: Hasher[F]): F[List[Signed[TokenLock]]]

  def acceptTokenLockRefs(
    lastTokenLockRefs: SortedMap[Address, TokenLockReference],
    lastTokenLockContextUpdate: Map[Address, TokenLockReference]
  ): SortedMap[Address, TokenLockReference]

  def filterExpiredTokenLocks(
    tokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
    epochProgress: EpochProgress
  ): SortedMap[Address, SortedSet[Signed[TokenLock]]]

  def updateTokenLockBalances(
    currencySnapshots: SortedMap[Address, CurrencySnapshotWithState],
    maybeLastTokenLockBalances: Option[SortedMap[Address, SortedMap[Address, Balance]]]
  ): SortedMap[Address, SortedMap[Address, Balance]]

  def updateGlobalBalancesByTokenLocks(
    epochProgress: EpochProgress,
    currentBalances: SortedMap[Address, Balance],
    acceptedGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
    lastActiveGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
    generatedTokenUnlocksByAddress: Map[Address, List[TokenUnlock]]
  ): Either[BalanceArithmeticError, SortedMap[Address, Balance]]

  def generateTokenUnlocks(
    expiredWithdrawals: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
    acceptedTokenLocks: List[Signed[TokenLock]],
    globalActiveTokenLocksByRef: Map[Hash, Signed[TokenLock]]
  ): Either[String, Map[Address, List[TokenUnlock]]]
}

object TokenLockStateManager {

  def make[F[_]: Async](): TokenLockStateManager[F] = new TokenLockStateManager[F] {

    def acceptTokenLocks(
      epochProgress: EpochProgress,
      acceptedGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      lastActiveGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      generatedTokenUnlocksByAddress: Map[Address, List[TokenUnlock]]
    )(implicit hasher: Hasher[F]): F[SortedMap[Address, SortedSet[Signed[TokenLock]]]] = {
      val expiredGlobalTokenLocks = filterExpiredTokenLocks(lastActiveGlobalTokenLocks, epochProgress)

      (acceptedGlobalTokenLocks |+| expiredGlobalTokenLocks).toList
        .foldM(lastActiveGlobalTokenLocks) {
          case (acc, (address, tokenLocks)) =>
            val lastAddressTokenLocks = acc.getOrElse(address, SortedSet.empty[Signed[TokenLock]])
            val unexpired = (lastAddressTokenLocks ++ tokenLocks).filter(_.unlockEpoch.forall(_ >= epochProgress))
            val addressTokenUnlocks = generatedTokenUnlocksByAddress.getOrElse(address, List.empty)
            val unlocksRefs = addressTokenUnlocks.map(_.tokenLockRef)

            unexpired
              .foldM(SortedSet.empty[Signed[TokenLock]]) { (acc, tokenLock) =>
                tokenLock.toHashed.map { tlh =>
                  if (unlocksRefs.contains(tlh.hash)) acc
                  else acc + tokenLock
                }
              }
              .map { updatedLocks =>
                acc.updated(address, updatedLocks)
              }
        }
        .map(updateTokenLocks => updateTokenLocks.filterNot(_._2.isEmpty))
    }
    def acceptReplacementTokenLocks(
      acceptedTokenLocks: List[Signed[TokenLock]],
      lastSnapshotContext: GlobalSnapshotInfo
    )(implicit hasher: Hasher[F]): F[List[Signed[TokenLock]]] =
      acceptedTokenLocks.filterA { tx =>
        tx.replaceTokenLockRef match {
          case Some(replaceTokenLockRef) =>
            if (tx.currencyId.nonEmpty) {
              // we can only replace DAG token locks
              false.pure[F]
            } else {
              lastSnapshotContext.activeTokenLocks
                .getOrElse(SortedMap.empty[Address, SortedSet[Signed[TokenLock]]])
                .getOrElse(tx.source, SortedSet.empty[Signed[TokenLock]])
                .toList
                .filter(_.currencyId.isEmpty) // we can only replace DAG token locks
                .traverse(existing => TokenLockReference.of(existing).map(ref => (ref, existing)))
                .map(_.exists {
                  case (ref, existing) => ref.hash === replaceTokenLockRef && existing.source == tx.source && existing.amount < tx.amount
                })
            }
          case None => true.pure[F]
        }
      }

    def acceptTokenLockRefs(
      lastTokenLockRefs: SortedMap[Address, TokenLockReference],
      lastTokenLockContextUpdate: Map[Address, TokenLockReference]
    ): SortedMap[Address, TokenLockReference] =
      lastTokenLockRefs ++ lastTokenLockContextUpdate

    def filterExpiredTokenLocks(
      tokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      epochProgress: EpochProgress
    ): SortedMap[Address, SortedSet[Signed[TokenLock]]] =
      tokenLocks.view.mapValues(_.filter(_.unlockEpoch.exists(_ < epochProgress))).to(SortedMap)

    def updateTokenLockBalances(
      currencySnapshots: SortedMap[Address, CurrencySnapshotWithState],
      maybeLastTokenLockBalances: Option[SortedMap[Address, SortedMap[Address, Balance]]]
    ): SortedMap[Address, SortedMap[Address, Balance]] = {
      val lastTokenLockBalances = maybeLastTokenLockBalances.getOrElse(SortedMap.empty[Address, SortedMap[Address, Balance]])

      currencySnapshots.foldLeft(lastTokenLockBalances) {
        case (accTokenLockBalances, (metagraphId, currencySnapshotWithState)) =>
          val activeTokenLocks = currencySnapshotWithState match {
            case Left(_)          => SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]
            case Right((_, info)) => info.activeTokenLocks.getOrElse(SortedMap.empty[Address, SortedSet[Signed[TokenLock]]])
          }

          val metagraphTokenLocksAmounts = activeTokenLocks.foldLeft(SortedMap.empty[Address, Balance]) {
            case (accTokenLockBalances, addressTokenLocks) =>
              val (address, tokenLocks) = addressTokenLocks
              val amount = NonNegLong.unsafeFrom(tokenLocks.toList.map(_.amount.value.value).sum)
              accTokenLockBalances.updated(address, Balance(amount))
          }

          accTokenLockBalances + (metagraphId -> metagraphTokenLocksAmounts)
      }
    }

    def updateGlobalBalancesByTokenLocks(
      epochProgress: EpochProgress,
      currentBalances: SortedMap[Address, Balance],
      acceptedGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      lastActiveGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      generatedTokenUnlocksByAddress: Map[Address, List[TokenUnlock]]
    ): Either[BalanceArithmeticError, SortedMap[Address, Balance]] = {
      val expiredGlobalTokenLocks = filterExpiredTokenLocks(lastActiveGlobalTokenLocks, epochProgress)

      // First, process all addresses that have token locks
      val balancesAfterTokenLocks =
        (acceptedGlobalTokenLocks |+| expiredGlobalTokenLocks).foldLeft[Either[BalanceArithmeticError, SortedMap[Address, Balance]]](
          Right(currentBalances)
        ) {
          case (accEither, (address, tokenLocks)) =>
            for {
              acc <- accEither
              initialBalance = acc.getOrElse(address, Balance.empty)

              unexpiredBalance <- {
                val unexpired = tokenLocks.filter(_.unlockEpoch.forall(_ >= epochProgress))

                unexpired.foldLeft[Either[BalanceArithmeticError, Balance]](Right(initialBalance)) { (currentBalanceEither, tokenLock) =>
                  for {
                    currentBalance <- currentBalanceEither
                    balanceAfterAmount <- currentBalance.minus(TokenLockAmount.toAmount(tokenLock.amount))
                    balanceAfterFee <- balanceAfterAmount.minus(TokenLockFee.toAmount(tokenLock.fee))
                  } yield balanceAfterFee
                }
              }

              expiredBalance <- {
                val expired = tokenLocks.filter(_.unlockEpoch.exists(_ < epochProgress))

                expired.foldLeft[Either[BalanceArithmeticError, Balance]](Right(unexpiredBalance)) { (currentBalanceEither, tokenLock) =>
                  for {
                    currentBalance <- currentBalanceEither
                    balanceAfterExpiredAmount <- currentBalance.plus(TokenLockAmount.toAmount(tokenLock.amount))
                  } yield balanceAfterExpiredAmount
                }
              }
              addressTokenUnlocks = generatedTokenUnlocksByAddress.getOrElse(address, List.empty)
              finalBalance <-
                addressTokenUnlocks.foldLeft[Either[BalanceArithmeticError, Balance]](Right(expiredBalance)) {
                  case (currentBalanceEither, tokenUnlock) =>
                    for {
                      currentBalance <- currentBalanceEither
                      balanceAfterUnlock <- currentBalance.plus(TokenLockAmount.toAmount(tokenUnlock.amount))
                    } yield balanceAfterUnlock
                }

              updatedAcc = acc.updated(address, finalBalance)
            } yield updatedAcc
        }

      // Then, process token unlocks for addresses that don't have token locks
      balancesAfterTokenLocks.flatMap { balances =>
        val addressesWithTokenLocks = (acceptedGlobalTokenLocks.keySet ++ expiredGlobalTokenLocks.keySet).toSet
        val addressesWithTokenUnlocksOnly = generatedTokenUnlocksByAddress.keySet -- addressesWithTokenLocks

        addressesWithTokenUnlocksOnly.foldLeft[Either[BalanceArithmeticError, SortedMap[Address, Balance]]](
          Right(balances)
        ) {
          case (accEither, address) =>
            for {
              acc <- accEither
              initialBalance = acc.getOrElse(address, Balance.empty)
              addressTokenUnlocks = generatedTokenUnlocksByAddress.getOrElse(address, List.empty)
              finalBalance <-
                addressTokenUnlocks.foldLeft[Either[BalanceArithmeticError, Balance]](Right(initialBalance)) {
                  case (currentBalanceEither, tokenUnlock) =>
                    for {
                      currentBalance <- currentBalanceEither
                      balanceAfterUnlock <- currentBalance.plus(TokenLockAmount.toAmount(tokenUnlock.amount))
                    } yield balanceAfterUnlock
                }
              updatedAcc = acc.updated(address, finalBalance)
            } yield updatedAcc
        }
      }
    }

    def generateTokenUnlocks(
      expiredWithdrawals: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
      acceptedTokenLocks: List[Signed[TokenLock]],
      globalActiveTokenLocksByRef: Map[Hash, Signed[TokenLock]]
    ): Either[String, Map[Address, List[TokenUnlock]]] = {

      val increasedTokenLockUnlocks = acceptedTokenLocks
        .mapFilter(tl => tl.replaceTokenLockRef.tupleLeft(tl))
        .traverse {
          case (tokenLock, replaceRef) =>
            globalActiveTokenLocksByRef
              .get(replaceRef)
              .toRight(s"Token lock not found for replacement ref: $replaceRef")
              .map { activeTokenLock =>
                (
                  tokenLock.source,
                  TokenUnlock(
                    replaceRef,
                    activeTokenLock.amount,
                    activeTokenLock.currencyId,
                    activeTokenLock.source
                  )
                )
              }
        }
        .map(_.groupBy { case (address, _) => address }.view.mapValues(_.map { case (_, tokenUnlock) => tokenUnlock }).toMap)

      val expiredWithdrawalUnlocks = expiredWithdrawals.toList.traverse {
        case (address, withdrawals) =>
          withdrawals.toList.traverse { pw: PendingDelegatedStakeWithdrawal =>
            for {
              activeTokenLock <- globalActiveTokenLocksByRef
                .get(pw.tokenLockRef)
                .toRight(s"Token lock not found for ref: ${pw.tokenLockRef}")
            } yield
              TokenUnlock(
                pw.tokenLockRef,
                activeTokenLock.amount,
                activeTokenLock.currencyId,
                activeTokenLock.source
              )
          }.map(tokenUnlocks => address -> tokenUnlocks)
      }.map(_.toMap)

      for {
        withdrawalUnlocks <- expiredWithdrawalUnlocks
        replacedUnlocks <- increasedTokenLockUnlocks
      } yield {
        val allAddresses = withdrawalUnlocks.keySet ++ replacedUnlocks.keySet
        allAddresses.map { address =>
          val withdrawalList = withdrawalUnlocks.getOrElse(address, List.empty)
          val replacedList = replacedUnlocks.getOrElse(address, List.empty)
          address -> (withdrawalList ++ replacedList)
        }.toMap
      }
    }
  }
}
