package io.constellationnetwork.schema.mpt

import cats.Parallel
import cats.effect.Sync
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.schema.GlobalSnapshotInfo
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.swap.AllowSpend
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

    implicit class MptBuilderOps[F[_]: Parallel: Sync: Hasher](kvPairsF: F[Map[GlobalStateKey, Json]]) {
      def buildMpt: F[MptRoot] =
        for {
          kvPairs <- kvPairsF
          hexMap <- kvPairs.toList.parTraverse {
            case (key, value) => GlobalStateKey.toHex[F](key).map(_ -> value)
          }.map(_.toMap)
          mptRoot <- hexMap.isEmpty
            .pure[F]
            .ifM(
              ifTrue = MptRoot(Hash.empty).pure[F],
              ifFalse = MerklePatriciaTrie.make[F, Json](hexMap).map(_.rootHash)
            )
        } yield mptRoot
    }
  }
}
