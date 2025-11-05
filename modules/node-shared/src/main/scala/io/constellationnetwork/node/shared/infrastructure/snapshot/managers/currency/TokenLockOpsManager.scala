package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{SharedArtifact, TokenUnlock}
import io.constellationnetwork.schema.balance.{Balance, BalanceArithmeticError}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

class TokenLockOpsManager[F[_]: Async] {

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
    val expiredTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]] =
      filterExpiredTokenLocks(lastActiveTokenLocks, epochProgress)

    val allAddresses: SortedSet[Address] =
      SortedSet.from(
        lastActiveTokenLocks.keySet ++ acceptedTokenLocks.keySet ++ expiredTokenLocks.keySet
      )

    val unlockRefs: Set[Hash] = acceptedTokenUnlocks.map(_.tokenLockRef)

    allAddresses.toList
      .foldM(lastActiveTokenLocks) { (acc, address) =>
        val previous = acc.getOrElse(address, SortedSet.empty[Signed[TokenLock]])
        val incoming = acceptedTokenLocks.getOrElse(address, SortedSet.empty[Signed[TokenLock]])

        val merged = previous ++ incoming

        val unexpired = merged.filter(_.unlockEpoch.forall(_ >= epochProgress))

        unexpired.toList.traverse(_.toHashed).map { hashedLocks =>
          val stillActive = hashedLocks
            .filterNot(h => unlockRefs.contains(h.hash))
            .map(_.signed)
            .to(SortedSet)

          if (stillActive.nonEmpty) acc.updated(address, stillActive)
          else acc - address
        }
      }
      .map(updatedActive => (updatedActive, expiredTokenLocks))
  }

  def filterExpiredTokenLocks(
    activeTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
    epochProgress: EpochProgress
  ): SortedMap[Address, SortedSet[Signed[TokenLock]]] =
    activeTokenLocks.flatMap {
      case (address, tokenLocks) =>
        val expired = tokenLocks.filter(_.unlockEpoch.exists(_ < epochProgress))
        if (expired.nonEmpty) Some(address -> expired)
        else None
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
    val expiredLocks = filterExpiredTokenLocks(lastActiveTokenLocks, epochProgress)

    val allAddresses =
      lastActiveTokenLocks.keySet ++ acceptedTokenLocks.keySet ++ expiredLocks.keySet

    allAddresses.foldLeft[Either[BalanceArithmeticError, SortedMap[Address, Balance]]](Right(currentBalances)) {
      case (accEither, address) =>
        for {
          acc <- accEither
          startBalance = acc.getOrElse(address, Balance.empty)
          newLocks = acceptedTokenLocks.getOrElse(address, SortedSet.empty[Signed[TokenLock]])
          expiredForAddress = expiredLocks.getOrElse(address, SortedSet.empty[Signed[TokenLock]])

          afterNewLocks <- newLocks.foldLeft[Either[BalanceArithmeticError, Balance]](Right(startBalance)) {
            case (balEither, tokenLock) =>
              for {
                bal <- balEither
                minusAmount <- bal.minus(TokenLockAmount.toAmount(tokenLock.amount))
                minusFee <- minusAmount.minus(TokenLockFee.toAmount(tokenLock.fee))
              } yield minusFee
          }

          afterExpiredRefunds <- expiredForAddress.foldLeft[Either[BalanceArithmeticError, Balance]](Right(afterNewLocks)) {
            case (balEither, tokenLock) =>
              for {
                bal <- balEither
                refunded <- bal.plus(TokenLockAmount.toAmount(tokenLock.amount))
              } yield refunded
          }

          manualUnlocksForAddress = acceptedTokenUnlocks.filter(_.source == address)
          finalBalance <- manualUnlocksForAddress.foldLeft[Either[BalanceArithmeticError, Balance]](Right(afterExpiredRefunds)) {
            case (balEither, tokenUnlock) =>
              for {
                bal <- balEither
                refunded <- bal.plus(TokenLockAmount.toAmount(tokenUnlock.amount))
              } yield refunded
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
}

object TokenLockOpsManager {
  def make[F[_]: Async]: TokenLockOpsManager[F] =
    new TokenLockOpsManager[F]
}
