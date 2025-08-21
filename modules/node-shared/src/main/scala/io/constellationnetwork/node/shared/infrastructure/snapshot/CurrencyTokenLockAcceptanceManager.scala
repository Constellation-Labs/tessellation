package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact._
import io.constellationnetwork.schema.balance.{Balance, BalanceArithmeticError}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

trait CurrencyTokenLockAcceptanceManager[F[_]] {
  def acceptTokenLocks(
    epochProgress: EpochProgress,
    acceptedTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
    lastActiveTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
    acceptedTokenUnlocks: SortedSet[TokenUnlock]
  )(implicit hasher: Hasher[F]): F[
    (
      SortedMap[Address, SortedSet[Signed[TokenLock]]],
      SortedMap[Address, SortedSet[Signed[TokenLock]]]
    )
  ]

  def acceptTokenUnlocks(
    expiredTokenLockHashes: List[Hash],
    incomingTokenUnlocks: SortedSet[TokenUnlock],
    activeTokenLocksRefs: List[Hash]
  ): SortedSet[TokenUnlock]

  def updateBalancesByTokenLocks(
    epochProgress: EpochProgress,
    currentBalances: SortedMap[Address, Balance],
    acceptedTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
    lastActiveTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
    acceptedTokenUnlocks: SortedSet[TokenUnlock]
  ): Either[BalanceArithmeticError, SortedMap[Address, Balance]]

  def emitTokenUnlocks(
    acceptedTokenUnlocks: SortedSet[TokenUnlock],
    expiredTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]]
  )(implicit hasher: Hasher[F]): F[SortedSet[SharedArtifact]]

  def filterExpiredTokenLocks(
    tokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
    epochProgress: EpochProgress
  ): SortedMap[Address, SortedSet[Signed[TokenLock]]]
}

object CurrencyTokenLockAcceptanceManager {
  def make[F[_]: Async]: CurrencyTokenLockAcceptanceManager[F] = new CurrencyTokenLockAcceptanceManager[F] {

    def acceptTokenLocks(
      epochProgress: EpochProgress,
      acceptedTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      lastActiveTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      acceptedTokenUnlocks: SortedSet[TokenUnlock]
    )(implicit hasher: Hasher[F]): F[
      (
        SortedMap[Address, SortedSet[Signed[TokenLock]]],
        SortedMap[Address, SortedSet[Signed[TokenLock]]]
      )
    ] = {
      val expiredTokenLocks = filterExpiredTokenLocks(lastActiveTokenLocks, epochProgress)

      (acceptedTokenLocks |+| expiredTokenLocks).toList
        .foldM(lastActiveTokenLocks) {
          case (acc, (address, tokenLocks)) =>
            val lastAddressTokenLocks = acc.getOrElse(address, SortedSet.empty[Signed[TokenLock]])
            val unexpired = (lastAddressTokenLocks ++ tokenLocks).filter(_.unlockEpoch.forall(_ >= epochProgress))
            val unlocksRefs = acceptedTokenUnlocks.map(_.tokenLockRef)

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
        .map(updateTokenLocks => (updateTokenLocks, expiredTokenLocks))
    }

    def acceptTokenUnlocks(
      expiredTokenLockHashes: List[Hash],
      incomingTokenUnlocks: SortedSet[TokenUnlock],
      activeTokenLocksRefs: List[Hash]
    ): SortedSet[TokenUnlock] =
      incomingTokenUnlocks.filter { itu =>
        activeTokenLocksRefs.contains(itu.tokenLockRef) &&
        !expiredTokenLockHashes.contains(itu.tokenLockRef)
      }

    def updateBalancesByTokenLocks(
      epochProgress: EpochProgress,
      currentBalances: SortedMap[Address, Balance],
      acceptedTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      lastActiveTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      acceptedTokenUnlocks: SortedSet[TokenUnlock]
    ): Either[BalanceArithmeticError, SortedMap[Address, Balance]] = {
      val expiredGlobalTokenLocks = filterExpiredTokenLocks(lastActiveTokenLocks, epochProgress)

      (acceptedTokenLocks |+| expiredGlobalTokenLocks).foldLeft[Either[BalanceArithmeticError, SortedMap[Address, Balance]]](
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
            finalBalance <-
              acceptedTokenUnlocks.foldLeft[Either[BalanceArithmeticError, Balance]](Right(expiredBalance)) {
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

    def emitTokenUnlocks(
      acceptedTokenUnlocks: SortedSet[TokenUnlock],
      expiredTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]]
    )(implicit hasher: Hasher[F]): F[SortedSet[SharedArtifact]] = {
      val acceptedTokenUnlocksHashes = acceptedTokenUnlocks.map(_.tokenLockRef)

      expiredTokenLocks.values.flatten.toList
        .traverse(_.toHashed)
        .map { hashedLocks =>
          val newUnlocks = hashedLocks.collect {
            case hashed if !acceptedTokenUnlocksHashes.contains(hashed.hash) =>
              TokenUnlock(
                hashed.hash,
                hashed.amount,
                hashed.currencyId,
                hashed.source
              )
          }

          val newUnlocksAsShared: SortedSet[SharedArtifact] =
            SortedSet.from[SharedArtifact](newUnlocks)
          val acceptedUnlocksAsShared: SortedSet[SharedArtifact] =
            SortedSet.from[SharedArtifact](acceptedTokenUnlocks)

          newUnlocksAsShared ++ acceptedUnlocksAsShared
        }
    }

    def filterExpiredTokenLocks(
      tokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      epochProgress: EpochProgress
    ): SortedMap[Address, SortedSet[Signed[TokenLock]]] =
      tokenLocks.view.mapValues(_.filter(_.unlockEpoch.exists(_ < epochProgress))).to(SortedMap)

  }
}
