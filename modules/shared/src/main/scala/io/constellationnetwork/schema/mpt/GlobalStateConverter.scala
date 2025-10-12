package io.constellationnetwork.schema.mpt

import cats.effect.Sync
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.schema.GlobalSnapshotInfo
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.swap.AllowSpend
import io.constellationnetwork.security.signature.Signed

import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json}

object GlobalStateConverter {

  def toKeyValuePairs[F[_]: Sync](
    info: GlobalSnapshotInfo
  ): F[Map[GlobalStateKey, Json]] = {

    def flattenAddressMap[A: Encoder](
      data: SortedMap[Address, A],
      fieldId: GlobalStateFieldId
    ): Map[GlobalStateKey, Json] =
      data.toSeq.map {
        case (addr, value) =>
          GlobalStateKey(fieldId, None, Some(addr), None) -> value.asJson
      }.toMap

    def flattenOptionalAddressMap[A: Encoder](
      dataOpt: Option[SortedMap[Address, A]],
      fieldId: GlobalStateFieldId
    ): Map[GlobalStateKey, Json] =
      dataOpt.map(flattenAddressMap(_, fieldId)).getOrElse(Map.empty)

    def flattenTokenLockBalances(
      dataOpt: Option[SortedMap[Address, SortedMap[Address, Balance]]]
    ): Map[GlobalStateKey, Json] =
      dataOpt.map { outerMap =>
        outerMap.toSeq.flatMap {
          case (tokenAddr, innerMap) =>
            innerMap.toSeq.map {
              case (holderAddr, balance) =>
                GlobalStateKey(GlobalStateFieldId.TokenLockBalances, None, Some(tokenAddr), Some(holderAddr)) -> balance.asJson
            }
        }.toMap
      }.getOrElse(Map.empty)

    def flattenActiveAllowSpends(
      dataOpt: Option[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]]
    ): Map[GlobalStateKey, Json] =
      dataOpt.map { outerMap =>
        outerMap.toSeq.flatMap {
          case (optAddr, innerMap) =>
            innerMap.toSeq.map {
              case (addr, allowSpends) =>
                GlobalStateKey(GlobalStateFieldId.ActiveAllowSpends, optAddr, Some(addr), None) -> allowSpends.asJson
            }
        }.toMap
      }.getOrElse(Map.empty)

    def flattenLastCurrencySnapshots(
      data: SortedMap[Address, Either[Signed[CurrencySnapshot], (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]]
    ): Map[GlobalStateKey, Json] =
      data.toSeq.flatMap {
        case (metagraphAddr, Left(fullSnapshot)) =>
          List(
            GlobalStateKey(GlobalStateFieldId.LastCurrencySnapshots, Some(metagraphAddr), None, None) -> fullSnapshot.asJson
          )
        case (metagraphAddr, Right((incrementalSnapshot, snapshotInfo))) =>
          List(
            GlobalStateKey(GlobalStateFieldId.LastCurrencySnapshots, Some(metagraphAddr), None, None) -> incrementalSnapshot.asJson,
            GlobalStateKey(GlobalStateFieldId.LastCurrencySnapshotsProofs, Some(metagraphAddr), None, None) -> snapshotInfo.asJson
          )
      }.toMap

    val allPairs =
      flattenAddressMap(info.lastStateChannelSnapshotHashes, GlobalStateFieldId.LastStateChannelSnapshotHashes) ++
        flattenAddressMap(info.lastTxRefs, GlobalStateFieldId.LastTxRefs) ++
        flattenAddressMap(info.balances, GlobalStateFieldId.Balances) ++
        flattenLastCurrencySnapshots(info.lastCurrencySnapshots) ++
        flattenAddressMap(info.lastCurrencySnapshotsProofs, GlobalStateFieldId.LastCurrencySnapshotsProofs) ++
        flattenActiveAllowSpends(info.activeAllowSpends) ++
        flattenOptionalAddressMap(info.activeTokenLocks, GlobalStateFieldId.ActiveTokenLocks) ++
        flattenTokenLockBalances(info.tokenLockBalances) ++
        flattenOptionalAddressMap(info.lastAllowSpendRefs, GlobalStateFieldId.LastAllowSpendRefs) ++
        flattenOptionalAddressMap(info.lastTokenLockRefs, GlobalStateFieldId.LastTokenLockRefs) ++
        flattenOptionalAddressMap(info.activeDelegatedStakes, GlobalStateFieldId.ActiveDelegatedStakes) ++
        flattenOptionalAddressMap(info.delegatedStakesWithdrawals, GlobalStateFieldId.DelegatedStakesWithdrawals) ++
        flattenOptionalAddressMap(info.activeNodeCollaterals, GlobalStateFieldId.ActiveNodeCollaterals) ++
        flattenOptionalAddressMap(info.nodeCollateralWithdrawals, GlobalStateFieldId.NodeCollateralWithdrawals) ++
        flattenOptionalAddressMap(info.metagraphSyncData, GlobalStateFieldId.MetagraphSyncData)

    allPairs.pure[F]
  }

  def fromKeyValuePairs[F[_]: Sync](
    pairs: Map[GlobalStateKey, Json]
  ): F[GlobalSnapshotInfo] =
    GlobalSnapshotInfo.empty.pure[F]
}
