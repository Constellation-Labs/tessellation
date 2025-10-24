package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact._
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.syntax.sortedCollection.sortedSetSyntax

trait ArtifactEmissionManager[F[_]] {
  def emitAllowSpendsExpired(
    addressToSet: SortedMap[Address, SortedSet[Signed[AllowSpend]]]
  )(implicit hasher: Hasher[F]): F[SortedSet[SharedArtifact]]

  def emitTokenUnlocks(
    expiredTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]]
  )(implicit hasher: Hasher[F]): F[SortedSet[SharedArtifact]]

  def emitAllExpiredArtifacts(
    expiredAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
    expiredTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]]
  )(implicit hasher: Hasher[F]): F[SortedSet[SharedArtifact]]
}

object ArtifactEmissionManager {

  def make[F[_]: Async: Parallel](): ArtifactEmissionManager[F] = new ArtifactEmissionManager[F] {

    def emitAllowSpendsExpired(
      addressToSet: SortedMap[Address, SortedSet[Signed[AllowSpend]]]
    )(implicit hasher: Hasher[F]): F[SortedSet[SharedArtifact]] =
      addressToSet.values.flatten.toList
        .traverse(_.toHashed)
        .map(_.map(hashed => AllowSpendExpiration(hashed.hash): SharedArtifact).toSortedSet)

    def emitTokenUnlocks(
      expiredTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]]
    )(implicit hasher: Hasher[F]): F[SortedSet[SharedArtifact]] =
      expiredTokenLocks.values.flatten.toList
        .traverse(_.toHashed)
        .map { hashedLocks =>
          val newUnlocks = hashedLocks.collect {
            case hashed =>
              TokenUnlock(
                hashed.hash,
                hashed.amount,
                hashed.currencyId,
                hashed.source
              )
          }
          SortedSet.from[SharedArtifact](newUnlocks)
        }

    def emitAllExpiredArtifacts(
      expiredAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      expiredTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]]
    )(implicit hasher: Hasher[F]): F[SortedSet[SharedArtifact]] =
      (
        emitAllowSpendsExpired(expiredAllowSpends),
        emitTokenUnlocks(expiredTokenLocks)
      ).parMapN { (allowSpendArtifacts, tokenUnlockArtifacts) =>
        allowSpendArtifacts ++ tokenUnlockArtifacts
      }
  }
}
