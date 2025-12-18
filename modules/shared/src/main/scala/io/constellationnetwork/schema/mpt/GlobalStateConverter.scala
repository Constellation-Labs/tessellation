package io.constellationnetwork.schema.mpt

import cats.Parallel
import cats.effect.{Async, Sync}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.merkletree.Proof
import io.constellationnetwork.schema.GlobalSnapshotInfo
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeRecord, PendingDelegatedStakeWithdrawal}
import io.constellationnetwork.schema.mpt.PartitionNamespace.AddressNamespace
import io.constellationnetwork.schema.nodeCollateral.{NodeCollateralRecord, PendingNodeCollateralWithdrawal}
import io.constellationnetwork.schema.snapshot.MetagraphSyncDataInfo
import io.constellationnetwork.schema.swap.{AllowSpend, AllowSpendReference}
import io.constellationnetwork.schema.tokenLock.{TokenLock, TokenLockReference}
import io.constellationnetwork.schema.transaction.TransactionReference
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.mpt.{MerklePatriciaTrie, MptRoot}
import io.constellationnetwork.security.signature.Signed

import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json}

object GlobalStateConverter {

  private def convertRequiredHypergraph[F[_]: Sync: Parallel, A: Encoder](
    data: SortedMap[Address, A],
    fieldId: GlobalStateFieldId
  ): F[Map[GlobalStateKey, Json]] =
    data.toSeq.parTraverse {
      case (addr, value) =>
        (GlobalStateKey.hypergraph(fieldId, addr) -> value.asJson).pure[F]
    }
      .map(_.toMap)

  private def convertRequiredMetagraph[F[_]: Sync: Parallel, A: Encoder](
    data: SortedMap[Address, A],
    fieldId: GlobalStateFieldId
  ): F[Map[GlobalStateKey, Json]] =
    data.toSeq.parTraverse {
      case (addr, value) =>
        (GlobalStateKey.metagraph(addr, fieldId) -> value.asJson).pure[F]
    }
      .map(_.toMap)

  private def convertOptionalHypergraph[F[_]: Sync, A: Encoder](
    dataOpt: Option[SortedMap[Address, A]],
    fieldId: GlobalStateFieldId
  ): F[Map[GlobalStateKey, Json]] =
    dataOpt
      .map(_.toSeq.map {
        case (addr, value) =>
          GlobalStateKey.hypergraph(fieldId, addr) -> value.asJson
      }.toMap)
      .getOrElse(Map.empty)
      .pure[F]

  private def convertCurrencySnapshots[F[_]: Sync: Parallel](
    data: SortedMap[Address, Either[Signed[CurrencySnapshot], (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]]
  ): F[Map[GlobalStateKey, Json]] =
    data.toSeq.parTraverse {
      case (metagraphAddr, Left(fullSnapshot)) =>
        List(
          GlobalStateKey.metagraph(metagraphAddr, GlobalStateFieldId.LastCurrencySnapshots) -> fullSnapshot.asJson
        ).pure[F]
      case (metagraphAddr, Right((incrementalSnapshot, snapshotInfo))) =>
        List(
          GlobalStateKey.metagraph(metagraphAddr, GlobalStateFieldId.LastIncrementalCurrencySnapshots) -> incrementalSnapshot.asJson,
          GlobalStateKey.metagraph(metagraphAddr, GlobalStateFieldId.LastCurrencySnapshotInfo) -> snapshotInfo.asJson
        ).pure[F]
    }
      .map(_.flatten.toMap)

  private def convertActiveAllowSpends[F[_]: Sync](
    dataOpt: Option[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]]
  ): F[Map[GlobalStateKey, Json]] =
    dataOpt
      .map(_.toSeq.flatMap {
        case (optAddr, innerMap) =>
          innerMap.toSeq.map {
            case (addr, allowSpends) =>
              GlobalStateKey.hypergraph(GlobalStateFieldId.ActiveAllowSpends, optAddr, addr) -> allowSpends.asJson
          }
      }.toMap)
      .getOrElse(Map.empty)
      .pure[F]

  private def convertTokenLockBalances[F[_]: Sync](
    dataOpt: Option[SortedMap[Address, SortedMap[Address, Balance]]]
  ): F[Map[GlobalStateKey, Json]] =
    dataOpt
      .map(_.toSeq.flatMap {
        case (tokenAddr, innerMap) =>
          innerMap.toSeq.map {
            case (holderAddr, balance) =>
              GlobalStateKey.hypergraph(GlobalStateFieldId.TokenLockBalances, tokenAddr, holderAddr) -> balance.asJson
          }
      }.toMap)
      .getOrElse(Map.empty)
      .pure[F]

  def toAllStateKeyValuePairs[F[_]: Sync: Parallel](
    info: GlobalSnapshotInfo
  ): F[Map[GlobalStateKey, Json]] =
    (
      convertRequiredMetagraph(info.lastStateChannelSnapshotHashes, GlobalStateFieldId.LastStateChannelSnapshotHashes),
      convertRequiredHypergraph(info.lastTxRefs, GlobalStateFieldId.LastTxRefs),
      convertRequiredHypergraph(info.balances, GlobalStateFieldId.Balances),
      convertCurrencySnapshots(info.lastCurrencySnapshots),
      convertRequiredMetagraph(info.lastCurrencySnapshotsProofs, GlobalStateFieldId.LastCurrencySnapshotsProofs),
      convertActiveAllowSpends(info.activeAllowSpends),
      convertOptionalHypergraph(info.activeTokenLocks, GlobalStateFieldId.ActiveTokenLocks),
      convertTokenLockBalances(info.tokenLockBalances),
      convertOptionalHypergraph(info.lastAllowSpendRefs, GlobalStateFieldId.LastAllowSpendRefs),
      convertOptionalHypergraph(info.lastTokenLockRefs, GlobalStateFieldId.LastTokenLockRefs),
      convertOptionalHypergraph(info.activeDelegatedStakes, GlobalStateFieldId.ActiveDelegatedStakes),
      convertOptionalHypergraph(info.delegatedStakesWithdrawals, GlobalStateFieldId.DelegatedStakesWithdrawals),
      convertOptionalHypergraph(info.activeNodeCollaterals, GlobalStateFieldId.ActiveNodeCollaterals),
      convertOptionalHypergraph(info.nodeCollateralWithdrawals, GlobalStateFieldId.NodeCollateralWithdrawals),
      convertOptionalHypergraph(info.metagraphSyncData, GlobalStateFieldId.MetagraphSyncData)
    ).parMapN { (m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15) =>
      List(m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15)
        .flatMap(_.toList)
        .foldLeft(Right(Map.empty[GlobalStateKey, Json]): Either[Throwable, Map[GlobalStateKey, Json]]) {
          case (Right(acc), (k, v)) =>
            if (acc.contains(k))
              Left(new IllegalStateException(s"Duplicate key found: $k"))
            else
              Right(acc.updated(k, v))
          case (left @ Left(_), _) => left
        }
    }.flatMap(_.liftTo[F])

  object syntax {
    implicit class GlobalSnapshotInfoMptOps(val info: GlobalSnapshotInfo) extends AnyVal {
      def allStateEntries[F[_]: Sync: Parallel]: F[Map[GlobalStateKey, Json]] =
        toAllStateKeyValuePairs(info)
    }

    implicit class MptBuilderOps[F[_]: Parallel: Async: Hasher](kvPairsF: F[Map[GlobalStateKey, Json]]) {
      def buildMpt: F[MptRoot] =
        for {
          kvPairs <- kvPairsF
          _ <- Async[F].cede
          hexMap <- kvPairs.toList.parTraverse {
            case (key, value) => GlobalStateKey.toHex[F](key).map(_ -> value)
          }.map(_.toMap)
          _ <- Async[F].cede
          mptRoot <- hexMap.isEmpty
            .pure[F]
            .ifM(
              ifTrue = MptRoot(Hash.empty).pure[F],
              ifFalse = MerklePatriciaTrie.make[F, Json](hexMap).map(_.rootHash)
            )
          _ <- Async[F].cede
        } yield mptRoot
    }

    implicit class MptStoreGlobalSnapshotOps[F[_]: Async: Parallel: Hasher](
      val store: MptStore[F, GlobalStateKey]
    ) {
      def syncFromGlobalSnapshotInfo(info: GlobalSnapshotInfo): F[Unit] =
        info.allStateEntries[F].flatMap(store.sync[Json])

      def getBalance(address: Address): F[Option[Balance]] =
        store
          .get[Balance](GlobalStateKey.hypergraph(GlobalStateFieldId.Balances, address))

      def getBalances(addresses: List[Address]): F[Map[Address, Balance]] = {
        val keys = addresses.map(addr => GlobalStateKey.hypergraph(GlobalStateFieldId.Balances, addr))
        store.getMany[Balance](keys).map { results =>
          results.flatMap {
            case (key, balance) =>
              key.userNamespace match {
                case AddressNamespace(addr) => Some(addr -> balance)
                case _                      => None
              }
          }
        }
      }

      def getTxRef(address: Address): F[Option[TransactionReference]] =
        store
          .get[TransactionReference](GlobalStateKey.hypergraph(GlobalStateFieldId.LastTxRefs, address))

      def getStateChannelHash(metagraphAddress: Address): F[Option[Hash]] =
        store
          .get[Hash](GlobalStateKey.metagraph(metagraphAddress, GlobalStateFieldId.LastStateChannelSnapshotHashes))

      def getAllowSpendRef(address: Address): F[Option[AllowSpendReference]] =
        store
          .get[AllowSpendReference](GlobalStateKey.hypergraph(GlobalStateFieldId.LastAllowSpendRefs, address))

      def getActiveAllowSpends(metagraphId: Option[Address], address: Address): F[Option[SortedSet[Signed[AllowSpend]]]] =
        store
          .get[SortedSet[Signed[AllowSpend]]](GlobalStateKey.hypergraph(GlobalStateFieldId.ActiveAllowSpends, metagraphId, address))

      def getTokenLockRef(address: Address): F[Option[TokenLockReference]] =
        store
          .get[TokenLockReference](GlobalStateKey.hypergraph(GlobalStateFieldId.LastTokenLockRefs, address))

      def getActiveTokenLocks(address: Address): F[Option[SortedSet[Signed[TokenLock]]]] =
        store
          .get[SortedSet[Signed[TokenLock]]](GlobalStateKey.hypergraph(GlobalStateFieldId.ActiveTokenLocks, address))

      def getTokenLockBalance(tokenAddress: Address, holderAddress: Address): F[Option[Balance]] =
        store
          .get[Balance](GlobalStateKey.hypergraph(GlobalStateFieldId.TokenLockBalances, tokenAddress, holderAddress))

      def getDelegatedStakes(address: Address): F[Option[SortedSet[DelegatedStakeRecord]]] =
        store
          .get[SortedSet[DelegatedStakeRecord]](GlobalStateKey.hypergraph(GlobalStateFieldId.ActiveDelegatedStakes, address))

      def getDelegatedStakeWithdrawals(address: Address): F[Option[SortedSet[PendingDelegatedStakeWithdrawal]]] =
        store
          .get[SortedSet[PendingDelegatedStakeWithdrawal]](
            GlobalStateKey.hypergraph(GlobalStateFieldId.DelegatedStakesWithdrawals, address)
          )

      def getNodeCollaterals(address: Address): F[Option[SortedSet[NodeCollateralRecord]]] =
        store
          .get[SortedSet[NodeCollateralRecord]](GlobalStateKey.hypergraph(GlobalStateFieldId.ActiveNodeCollaterals, address))

      def getNodeCollateralWithdrawals(address: Address): F[Option[SortedSet[PendingNodeCollateralWithdrawal]]] =
        store
          .get[SortedSet[PendingNodeCollateralWithdrawal]](GlobalStateKey.hypergraph(GlobalStateFieldId.NodeCollateralWithdrawals, address))

      def getCurrencySnapshot(metagraphAddress: Address): F[Option[Signed[CurrencySnapshot]]] =
        store
          .get[Signed[CurrencySnapshot]](GlobalStateKey.metagraph(metagraphAddress, GlobalStateFieldId.LastCurrencySnapshots))

      def getIncrementalCurrencySnapshot(metagraphAddress: Address): F[Option[Signed[CurrencyIncrementalSnapshot]]] =
        store
          .get[Signed[CurrencyIncrementalSnapshot]](
            GlobalStateKey.metagraph(metagraphAddress, GlobalStateFieldId.LastIncrementalCurrencySnapshots)
          )

      def getCurrencySnapshotInfo(metagraphAddress: Address): F[Option[CurrencySnapshotInfo]] =
        store
          .get[CurrencySnapshotInfo](GlobalStateKey.metagraph(metagraphAddress, GlobalStateFieldId.LastCurrencySnapshotInfo))

      def getCurrencySnapshotProof(metagraphAddress: Address): F[Option[Proof]] =
        store
          .get[Proof](GlobalStateKey.metagraph(metagraphAddress, GlobalStateFieldId.LastCurrencySnapshotsProofs))

      def getMetagraphSyncData(metagraphAddress: Address): F[Option[MetagraphSyncDataInfo]] =
        store
          .get[MetagraphSyncDataInfo](GlobalStateKey.hypergraph(GlobalStateFieldId.MetagraphSyncData, metagraphAddress))
    }
  }
}
