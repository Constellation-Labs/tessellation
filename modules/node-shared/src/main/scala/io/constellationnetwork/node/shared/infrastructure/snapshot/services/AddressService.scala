package io.constellationnetwork.node.shared.infrastructure.snapshot.services

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.node.shared.config.types.AddressesConfig
import io.constellationnetwork.node.shared.domain.snapshot.services.AddressService
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.delegatedStake.DelegatedStakeRecord
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo}
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.security.signature.Signed

import io.estatico.newtype.ops._

object AddressService {

  /** Only `getBalance` is migrated to MptStore for per-address O(log n) lookup. Aggregate methods (`getTotalSupply`, `getWalletCount`,
    * `getFilteredOutTotalSupply`, `getCirculatedSupply`, `getFilteredOutCirculatedSupply`) intentionally remain on `SnapshotInfo` because
    * they iterate all balances/token-locks/delegated-stakes and cannot benefit from key-based MptStore access.
    */
  def make[F[_]: Async, S <: Snapshot, C <: SnapshotInfo[_]](
    addressCfg: AddressesConfig,
    snapshotStorage: SnapshotStorage[F, S, C],
    maybeMptStore: Option[MptStore[F, GlobalStateKey]] = None
  ): AddressService[F, S] =
    new AddressService[F, S] {

      def getBalance(address: Address): F[Option[(Balance, SnapshotOrdinal)]] =
        maybeMptStore match {
          case Some(mptStore) =>
            snapshotStorage.head.flatMap {
              case Some((snapshot, _)) =>
                mptStore.getBalance(address).map { maybeBalance =>
                  Some((maybeBalance.getOrElse(Balance.empty), snapshot.value.ordinal))
                }
              case None => Async[F].pure(None)
            }
          case None =>
            snapshotStorage.head.map(_.map {
              case (snapshot, state) =>
                val balance = state.balances.getOrElse(address, Balance.empty)
                val ordinal = snapshot.value.ordinal
                (balance, ordinal)
            })
        }

      def getTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]] =
        snapshotStorage.head.map(_.map {
          case (snapshot, state) =>
            calculateTotalSupply(
              state.balances.values,
              state.getActiveTokenLocks.values.flatten,
              state.getActiveDelegatedStakes.values.flatten,
              snapshot.value.ordinal
            )
        })

      def getWalletCount: F[Option[(Int, SnapshotOrdinal)]] =
        snapshotStorage.head.map(_.map {
          case (snapshot, state) =>
            val balance = state.balances.size
            val ordinal = snapshot.value.ordinal

            (balance, ordinal)
        })

      def getFilteredOutTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]] =
        snapshotStorage.head.map(_.map {
          case (snapshot, state) =>
            calculateTotalSupply(
              state.balances.filterNot { case (a, _) => addressCfg.locked.contains(a) }.values,
              state.getActiveTokenLocks.filterNot { case (a, _) => addressCfg.locked.contains(a) }.values.flatten,
              state.getActiveDelegatedStakes.filterNot { case (a, _) => addressCfg.locked.contains(a) }.values.flatten,
              snapshot.value.ordinal
            )
        })

      private def calculateTotalSupply(
        balances: Iterable[Balance],
        tokenLocks: Iterable[Signed[TokenLock]],
        delegatedStakes: Iterable[DelegatedStakeRecord],
        ordinal: SnapshotOrdinal
      ): (BigInt, SnapshotOrdinal) = {
        val empty = BigInt(Balance.empty.coerce.value)
        val supply = balances.foldLeft(empty) { (acc, b) =>
          acc + BigInt(b.coerce.value)
        } + tokenLocks.foldLeft(empty) { (acc, t) =>
          acc + BigInt(t.amount.coerce.value)
        } + delegatedStakes.foldLeft(empty) { (acc, s) =>
          acc + BigInt(s.rewards.coerce.value)
        }

        (supply, ordinal)
      }

      def getCirculatedSupply: F[Option[(BigInt, SnapshotOrdinal)]] =
        snapshotStorage.head.map(_.map {
          case (snapshot, state) =>
            calculateTotalSupply(
              state.balances.values,
              List.empty,
              List.empty,
              snapshot.value.ordinal
            )
        })

      def getFilteredOutCirculatedSupply: F[Option[(BigInt, SnapshotOrdinal)]] =
        snapshotStorage.head.map(_.map {
          case (snapshot, state) =>
            calculateTotalSupply(
              state.balances.filterNot { case (a, _) => addressCfg.locked.contains(a) }.values,
              List.empty,
              List.empty,
              snapshot.value.ordinal
            )
        })

    }
}
