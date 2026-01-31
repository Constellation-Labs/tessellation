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

/** Result of token lock acceptance containing full state, deltas, and removed keys */
case class TokenLockAcceptanceResult(
  fullState: SortedMap[Address, SortedSet[Signed[TokenLock]]],
  deltas: SortedMap[Address, SortedSet[Signed[TokenLock]]],
  removedKeys: Set[Address] = Set.empty
)

/** Result of token lock balance update containing full state and deltas */
case class TokenLockBalanceResult(
  fullState: SortedMap[Address, SortedMap[Address, Balance]],
  deltas: SortedMap[Address, SortedMap[Address, Balance]]
)

trait TokenLockStateManager[F[_]] {
  def acceptTokenLocks(
    epochProgress: EpochProgress,
    acceptedGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
    lastActiveGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
    generatedTokenUnlocksByAddress: Map[Address, List[TokenUnlock]]
  )(implicit hasher: Hasher[F]): F[TokenLockAcceptanceResult]

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
  ): TokenLockBalanceResult

  def updateGlobalBalancesByTokenLocks(
    epochProgress: EpochProgress,
    currentBalances: SortedMap[Address, Balance],
    acceptedGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
    lastActiveGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
    generatedTokenUnlocksByAddress: Map[Address, List[TokenUnlock]]
  ): Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedMap[Address, Balance])]

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
    )(implicit hasher: Hasher[F]): F[TokenLockAcceptanceResult] = {
      val expiredGlobalTokenLocks = filterExpiredTokenLocks(lastActiveGlobalTokenLocks, epochProgress)

      (acceptedGlobalTokenLocks |+| expiredGlobalTokenLocks).toList
        .foldM((lastActiveGlobalTokenLocks, SortedMap.empty[Address, SortedSet[Signed[TokenLock]]])) {
          case ((acc, deltas), (address, tokenLocks)) =>
            val lastAddressTokenLocks = acc.getOrElse(address, SortedSet.empty[Signed[TokenLock]])
            val unexpired = (lastAddressTokenLocks ++ tokenLocks).filter(_.unlockEpoch.forall(_ >= epochProgress))
            val addressTokenUnlocks = generatedTokenUnlocksByAddress.getOrElse(address, List.empty)
            val unlocksRefs = addressTokenUnlocks.map(_.tokenLockRef)

            unexpired
              .foldM(SortedSet.empty[Signed[TokenLock]]) { (innerAcc, tokenLock) =>
                tokenLock.toHashed.map { tlh =>
                  if (unlocksRefs.contains(tlh.hash)) innerAcc
                  else innerAcc + tokenLock
                }
              }
              .map { updatedLocks =>
                val hasChanged = lastAddressTokenLocks != updatedLocks
                val newAcc = acc.updated(address, updatedLocks)
                val newDeltas = if (hasChanged) deltas.updated(address, updatedLocks) else deltas
                (newAcc, newDeltas)
              }
        }
        .map {
          case (fullState, deltas) =>
            val cleanedFullState = fullState.filterNot(_._2.isEmpty)
            val cleanedDeltas = deltas.filterNot(_._2.isEmpty)

            // Compute removed keys: addresses that had TokenLocks but now have empty or missing sets
            val removedKeys: Set[Address] = lastActiveGlobalTokenLocks.collect {
              case (address, spends) if spends.nonEmpty && !cleanedFullState.get(address).exists(_.nonEmpty) =>
                address
            }.toSet

            TokenLockAcceptanceResult(cleanedFullState, cleanedDeltas, removedKeys)
        }
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
    ): TokenLockBalanceResult = {
      val lastTokenLockBalances = maybeLastTokenLockBalances.getOrElse(SortedMap.empty[Address, SortedMap[Address, Balance]])

      val (fullState, deltas) = currencySnapshots.foldLeft((lastTokenLockBalances, SortedMap.empty[Address, SortedMap[Address, Balance]])) {
        case ((accTokenLockBalances, accDeltas), (metagraphId, currencySnapshotWithState)) =>
          val activeTokenLocks = currencySnapshotWithState match {
            case Left(_)          => SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]
            case Right((_, info)) => info.activeTokenLocks.getOrElse(SortedMap.empty[Address, SortedSet[Signed[TokenLock]]])
          }

          val metagraphTokenLocksAmounts = activeTokenLocks.foldLeft(SortedMap.empty[Address, Balance]) {
            case (accBalances, addressTokenLocks) =>
              val (address, tokenLocks) = addressTokenLocks
              val amount = NonNegLong.unsafeFrom(tokenLocks.toList.map(_.amount.value.value).sum)
              accBalances.updated(address, Balance(amount))
          }

          val previousMetagraphBalances = accTokenLockBalances.getOrElse(metagraphId, SortedMap.empty[Address, Balance])
          val hasChanged = previousMetagraphBalances != metagraphTokenLocksAmounts

          val newAccTokenLockBalances = accTokenLockBalances + (metagraphId -> metagraphTokenLocksAmounts)
          val newAccDeltas = if (hasChanged) accDeltas + (metagraphId -> metagraphTokenLocksAmounts) else accDeltas

          (newAccTokenLockBalances, newAccDeltas)
      }

      TokenLockBalanceResult(fullState, deltas)
    }

    def updateGlobalBalancesByTokenLocks(
      epochProgress: EpochProgress,
      currentBalances: SortedMap[Address, Balance],
      acceptedGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      lastActiveGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      generatedTokenUnlocksByAddress: Map[Address, List[TokenUnlock]]
    ): Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedMap[Address, Balance])] = {
      val expiredGlobalTokenLocks = filterExpiredTokenLocks(lastActiveGlobalTokenLocks, epochProgress)

      // First, process all addresses that have token locks
      val balancesAfterTokenLocks =
        (acceptedGlobalTokenLocks |+| expiredGlobalTokenLocks)
          .foldLeft[Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedMap[Address, Balance])]](
            Right((currentBalances, SortedMap.empty[Address, Balance]))
          ) {
            case (accEither, (address, tokenLocks)) =>
              for {
                (balances, balancesDelta) <- accEither
                initialBalance = balances.getOrElse(address, Balance.empty)

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

                updatedAcc = balances.updated(address, finalBalance)
                updatedBalancesDelta = balancesDelta.updated(address, finalBalance)
              } yield (updatedAcc, updatedBalancesDelta)
          }

      // Then, process token unlocks for addresses that don't have token locks
      balancesAfterTokenLocks.flatMap { balancesAfter =>
        val addressesWithTokenLocks = acceptedGlobalTokenLocks.keySet ++ expiredGlobalTokenLocks.keySet
        val addressesWithTokenUnlocksOnly = generatedTokenUnlocksByAddress.keySet -- addressesWithTokenLocks

        addressesWithTokenUnlocksOnly.foldLeft[Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedMap[Address, Balance])]](
          Right(balancesAfter)
        ) {
          case (accEither, address) =>
            for {
              (balances, balancesDelta) <- accEither
              initialBalance = balances.getOrElse(address, Balance.empty)
              addressTokenUnlocks = generatedTokenUnlocksByAddress.getOrElse(address, List.empty)
              finalBalance <-
                addressTokenUnlocks.foldLeft[Either[BalanceArithmeticError, Balance]](Right(initialBalance)) {
                  case (currentBalanceEither, tokenUnlock) =>
                    for {
                      currentBalance <- currentBalanceEither
                      balanceAfterUnlock <- currentBalance.plus(TokenLockAmount.toAmount(tokenUnlock.amount))
                    } yield balanceAfterUnlock
                }
              updatedAcc = balances.updated(address, finalBalance)
              updatedBalancesDelta = balancesDelta.updated(address, finalBalance)
            } yield (updatedAcc, updatedBalancesDelta)
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
