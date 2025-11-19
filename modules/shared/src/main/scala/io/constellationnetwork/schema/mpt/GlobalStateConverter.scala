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

  def toStateChannelHashesKeyValuePairs[F[_]: Sync](
    info: GlobalSnapshotInfo
  ): F[Map[GlobalStateKey, Json]] =
    info.lastStateChannelSnapshotHashes.toSeq.map {
      case (addr, hash) =>
        GlobalStateKey.metagraph(addr, GlobalStateFieldId.LastStateChannelSnapshotHashes) -> hash.asJson
    }.toMap.pure[F]

  def toLastTxRefsKeyValuePairs[F[_]: Sync](
    info: GlobalSnapshotInfo
  ): F[Map[GlobalStateKey, Json]] =
    info.lastTxRefs.toSeq.map {
      case (addr, txRef) =>
        GlobalStateKey.hypergraph(GlobalStateFieldId.LastTxRefs, addr) -> txRef.asJson
    }.toMap.pure[F]

  def toBalancesKeyValuePairs[F[_]: Sync](
    info: GlobalSnapshotInfo
  ): F[Map[GlobalStateKey, Json]] =
    info.balances.toSeq.map {
      case (addr, balance) =>
        GlobalStateKey.hypergraph(GlobalStateFieldId.Balances, addr) -> balance.asJson
    }.toMap.pure[F]

  def toCurrencySnapshotsKeyValuePairs[F[_]: Sync](
    info: GlobalSnapshotInfo
  ): F[Map[GlobalStateKey, Json]] = {
    val snapshotPairs = info.lastCurrencySnapshots.toSeq.flatMap {
      case (metagraphAddr, Left(fullSnapshot)) =>
        List(
          GlobalStateKey.metagraph(metagraphAddr, GlobalStateFieldId.LastCurrencySnapshots) -> fullSnapshot.asJson
        )
      case (metagraphAddr, Right((incrementalSnapshot, snapshotInfo))) =>
        List(
          GlobalStateKey.metagraph(metagraphAddr, GlobalStateFieldId.LastIncrementalCurrencySnapshots) -> incrementalSnapshot.asJson,
          GlobalStateKey.metagraph(metagraphAddr, GlobalStateFieldId.LastCurrencySnapshotInfo) -> snapshotInfo.asJson
        )
    }

    val proofPairs = info.lastCurrencySnapshotsProofs.toSeq.map {
      case (addr, proof) =>
        GlobalStateKey.metagraph(addr, GlobalStateFieldId.LastCurrencySnapshotsProofs) -> proof.asJson
    }

    (snapshotPairs ++ proofPairs).toMap.pure[F]
  }

  def toOptionalFieldsKeyValuePairs[F[_]: Sync](
    info: GlobalSnapshotInfo
  ): F[Map[GlobalStateKey, Json]] = {

    def flattenHypergraphAddressMap[A: Encoder](
      dataOpt: Option[SortedMap[Address, A]],
      fieldId: GlobalStateFieldId
    ): Map[GlobalStateKey, Json] =
      dataOpt.map { data =>
        data.toSeq.map {
          case (addr, value) =>
            GlobalStateKey.hypergraph(fieldId, addr) -> value.asJson
        }.toMap
      }.getOrElse(Map.empty)

    def flattenTokenLockBalances(
      dataOpt: Option[SortedMap[Address, SortedMap[Address, Balance]]]
    ): Map[GlobalStateKey, Json] =
      dataOpt.map { outerMap =>
        outerMap.toSeq.flatMap {
          case (tokenAddr, innerMap) =>
            innerMap.toSeq.map {
              case (holderAddr, balance) =>
                GlobalStateKey.hypergraph(GlobalStateFieldId.TokenLockBalances, tokenAddr, holderAddr) -> balance.asJson
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
                GlobalStateKey.hypergraph(GlobalStateFieldId.ActiveAllowSpends, optAddr, addr) -> allowSpends.asJson
            }
        }.toMap
      }.getOrElse(Map.empty)

    val allPairs =
      flattenActiveAllowSpends(info.activeAllowSpends) ++
        flattenHypergraphAddressMap(info.activeTokenLocks, GlobalStateFieldId.ActiveTokenLocks) ++
        flattenTokenLockBalances(info.tokenLockBalances) ++
        flattenHypergraphAddressMap(info.lastAllowSpendRefs, GlobalStateFieldId.LastAllowSpendRefs) ++
        flattenHypergraphAddressMap(info.lastTokenLockRefs, GlobalStateFieldId.LastTokenLockRefs) ++
        flattenHypergraphAddressMap(info.activeDelegatedStakes, GlobalStateFieldId.ActiveDelegatedStakes) ++
        flattenHypergraphAddressMap(info.delegatedStakesWithdrawals, GlobalStateFieldId.DelegatedStakesWithdrawals) ++
        flattenHypergraphAddressMap(info.activeNodeCollaterals, GlobalStateFieldId.ActiveNodeCollaterals) ++
        flattenHypergraphAddressMap(info.nodeCollateralWithdrawals, GlobalStateFieldId.NodeCollateralWithdrawals) ++
        flattenHypergraphAddressMap(info.metagraphSyncData, GlobalStateFieldId.MetagraphSyncData)

    allPairs.pure[F]
  }

  object syntax {
    implicit class GlobalSnapshotInfoMptOps(val info: GlobalSnapshotInfo) extends AnyVal {
      def stateChannelHashesEntries[F[_]: Sync]: F[Map[GlobalStateKey, Json]] =
        toStateChannelHashesKeyValuePairs(info)

      def lastTxRefsEntries[F[_]: Sync]: F[Map[GlobalStateKey, Json]] =
        toLastTxRefsKeyValuePairs(info)

      def balancesEntries[F[_]: Sync]: F[Map[GlobalStateKey, Json]] =
        toBalancesKeyValuePairs(info)

      def currencySnapshotsEntries[F[_]: Sync]: F[Map[GlobalStateKey, Json]] =
        toCurrencySnapshotsKeyValuePairs(info)

      def auxiliaryStateEntries[F[_]: Sync]: F[Map[GlobalStateKey, Json]] =
        toOptionalFieldsKeyValuePairs(info)
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
