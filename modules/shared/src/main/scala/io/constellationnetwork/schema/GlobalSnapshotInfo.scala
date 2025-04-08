package io.constellationnetwork.schema

import cats.Parallel
import cats.effect.kernel.Sync
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.merkletree.syntax._
import io.constellationnetwork.merkletree.{MerkleRoot, MerkleTree, Proof}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeRecord, PendingWithdrawal, UpdateDelegatedStake}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.nodeCollateral.UpdateNodeCollateral
import io.constellationnetwork.schema.snapshot.{SnapshotInfo, StateProof}
import io.constellationnetwork.schema.swap.{AllowSpend, AllowSpendReference}
import io.constellationnetwork.schema.tokenLock.{TokenLock, TokenLockReference}
import io.constellationnetwork.schema.transaction.TransactionReference
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import io.circe.disjunctionCodecs._
import io.circe.{KeyDecoder, KeyEncoder}

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshotInfoV1(
  lastStateChannelSnapshotHashes: SortedMap[Address, Hash],
  lastTxRefs: SortedMap[Address, TransactionReference],
  balances: SortedMap[Address, Balance]
) extends SnapshotInfo[GlobalSnapshotStateProof] {
  def stateProof[F[_]: Parallel: Sync: Hasher](ordinal: SnapshotOrdinal): F[GlobalSnapshotStateProof] =
    GlobalSnapshotInfoV1.toGlobalSnapshotInfo(this).stateProof[F](ordinal)
}

object GlobalSnapshotInfoV1 {
  implicit def toGlobalSnapshotInfo(gsi: GlobalSnapshotInfoV1): GlobalSnapshotInfo =
    GlobalSnapshotInfo(
      gsi.lastStateChannelSnapshotHashes,
      gsi.lastTxRefs,
      gsi.balances,
      SortedMap.empty,
      SortedMap.empty,
      Some(SortedMap.empty[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]),
      Some(SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty)
    )
}

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshotStateProofV1(
  lastStateChannelSnapshotHashesProof: Hash,
  lastTxRefsProof: Hash,
  balancesProof: Hash,
  lastCurrencySnapshotsProof: Option[MerkleRoot]
) extends StateProof {
  def toGlobalSnapshotStateProof: GlobalSnapshotStateProof =
    GlobalSnapshotStateProof(
      lastStateChannelSnapshotHashesProof,
      lastTxRefsProof,
      balancesProof,
      lastCurrencySnapshotsProof,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None
    )
}

object GlobalSnapshotStateProofV1 {
  def apply: ((Hash, Hash, Hash, Option[MerkleRoot])) => GlobalSnapshotStateProofV1 = {
    case (x1, x2, x3, x4) => GlobalSnapshotStateProofV1.apply(x1, x2, x3, x4)
  }

  def fromGlobalSnapshotStateProof(proof: GlobalSnapshotStateProof): GlobalSnapshotStateProofV1 =
    GlobalSnapshotStateProofV1(
      proof.lastStateChannelSnapshotHashesProof,
      proof.lastTxRefsProof,
      proof.balancesProof,
      proof.lastCurrencySnapshotsProof
    )
}

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshotStateProof(
  lastStateChannelSnapshotHashesProof: Hash,
  lastTxRefsProof: Hash,
  balancesProof: Hash,
  lastCurrencySnapshotsProof: Option[MerkleRoot],
  activeAllowSpends: Option[Hash],
  activeTokenLocks: Option[Hash],
  tokenLockBalances: Option[Hash],
  lastAllowSpendRefs: Option[Hash],
  lastTokenLockRefs: Option[Hash],
  updateNodeParameters: Option[Hash],
  activeDelegatedStakes: Option[Hash],
  delegatedStakesWithdrawals: Option[Hash],
  activeNodeCollaterals: Option[Hash],
  nodeCollateralWithdrawals: Option[Hash]
) extends StateProof

object GlobalSnapshotStateProof {
  def apply: (
    (
      Hash,
      Hash,
      Hash,
      Option[MerkleRoot],
      Option[Hash],
      Option[Hash],
      Option[Hash],
      Option[Hash],
      Option[Hash],
      Option[Hash],
      Option[Hash],
      Option[Hash],
      Option[Hash],
      Option[Hash]
    )
  ) => GlobalSnapshotStateProof = {
    case (x1, x2, x3, x4, x5, x6, x7, x8, x9, x10, x11, x12, x13, x14) =>
      GlobalSnapshotStateProof.apply(x1, x2, x3, x4, x5, x6, x7, x8, x9, x10, x11, x12, x13, x14)
  }
}

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshotInfoV2(
  lastStateChannelSnapshotHashes: SortedMap[Address, Hash],
  lastTxRefs: SortedMap[Address, TransactionReference],
  balances: SortedMap[Address, Balance],
  lastCurrencySnapshots: SortedMap[Address, Either[Signed[
    CurrencySnapshot
  ], (Signed[CurrencyIncrementalSnapshotV1], CurrencySnapshotInfoV1)]],
  lastCurrencySnapshotsProofs: SortedMap[Address, Proof]
) extends SnapshotInfo[GlobalSnapshotStateProof] {
  def toGlobalSnapshotInfo: GlobalSnapshotInfo =
    GlobalSnapshotInfo(
      lastStateChannelSnapshotHashes,
      lastTxRefs,
      balances,
      lastCurrencySnapshots.view.mapValues {
        _.map { case (Signed(inc, proofs), info) => (Signed(inc.toCurrencyIncrementalSnapshot, proofs), info.toCurrencySnapshotInfo) }
      }.to(lastCurrencySnapshots.sortedMapFactory),
      lastCurrencySnapshotsProofs,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None
    )

  def stateProof[F[_]: Parallel: Sync: Hasher](ordinal: SnapshotOrdinal): F[GlobalSnapshotStateProof] =
    lastCurrencySnapshots.merkleTree[F].flatMap(stateProof(_))

  def stateProof[F[_]: Parallel: Sync: Hasher](lastCurrencySnapshots: Option[MerkleTree]): F[GlobalSnapshotStateProof] =
    toGlobalSnapshotInfo.stateProof[F](lastCurrencySnapshots)

}

object GlobalSnapshotInfoV2 {
  def fromGlobalSnapshotInfo(gs: GlobalSnapshotInfo): GlobalSnapshotInfoV2 =
    GlobalSnapshotInfoV2(
      gs.lastStateChannelSnapshotHashes,
      gs.lastTxRefs,
      gs.balances,
      gs.lastCurrencySnapshots.view.mapValues {
        _.map {
          case (Signed(inc, proofs), info) =>
            (
              Signed(CurrencyIncrementalSnapshotV1.fromCurrencyIncrementalSnapshot(inc), proofs),
              CurrencySnapshotInfoV1.fromCurrencySnapshotInfo(info)
            )
        }
      }.to(gs.lastCurrencySnapshots.sortedMapFactory),
      gs.lastCurrencySnapshotsProofs
    )
}

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshotInfo(
  lastStateChannelSnapshotHashes: SortedMap[Address, Hash],
  lastTxRefs: SortedMap[Address, TransactionReference],
  balances: SortedMap[Address, Balance],
  lastCurrencySnapshots: SortedMap[Address, Either[Signed[CurrencySnapshot], (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]],
  lastCurrencySnapshotsProofs: SortedMap[Address, Proof],
  activeAllowSpends: Option[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]],
  activeTokenLocks: Option[SortedMap[Address, SortedSet[Signed[TokenLock]]]],
  tokenLockBalances: Option[SortedMap[Address, SortedMap[Address, Balance]]],
  lastAllowSpendRefs: Option[SortedMap[Address, AllowSpendReference]],
  lastTokenLockRefs: Option[SortedMap[Address, TokenLockReference]],
  updateNodeParameters: Option[SortedMap[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)]],
  activeDelegatedStakes: Option[SortedMap[Address, List[DelegatedStakeRecord]]],
  delegatedStakesWithdrawals: Option[SortedMap[Address, List[PendingWithdrawal]]],
  activeNodeCollaterals: Option[SortedMap[Address, List[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)]]],
  nodeCollateralWithdrawals: Option[SortedMap[Address, List[(Signed[UpdateNodeCollateral.Withdraw], EpochProgress)]]]
) extends SnapshotInfo[GlobalSnapshotStateProof] {
  def stateProof[F[_]: Parallel: Sync: Hasher](ordinal: SnapshotOrdinal): F[GlobalSnapshotStateProof] =
    lastCurrencySnapshots.merkleTree[F].flatMap(stateProof(_))

  implicit val optionAddressKeyEncoder: KeyEncoder[Option[Address]] = new KeyEncoder[Option[Address]] {
    def apply(key: Option[Address]): String = key match {
      case None          => KeyEncoder[String].apply("")
      case Some(address) => KeyEncoder[Address].apply(address)
    }
  }

  def stateProof[F[_]: Parallel: Sync: Hasher](lastCurrencySnapshots: Option[MerkleTree]): F[GlobalSnapshotStateProof] =
    (
      lastStateChannelSnapshotHashes.hash,
      lastTxRefs.hash,
      balances.hash,
      activeAllowSpends.parTraverse(_.hash),
      activeTokenLocks.parTraverse(_.hash),
      tokenLockBalances.parTraverse(_.hash),
      lastAllowSpendRefs.parTraverse(_.hash),
      lastTokenLockRefs.parTraverse(_.hash),
      updateNodeParameters.parTraverse(_.hash),
      activeDelegatedStakes.parTraverse(_.hash),
      delegatedStakesWithdrawals.parTraverse(_.hash),
      activeNodeCollaterals.parTraverse(_.hash),
      nodeCollateralWithdrawals.parTraverse(_.hash)
    ).mapN(GlobalSnapshotStateProof.apply(_, _, _, lastCurrencySnapshots.map(_.getRoot), _, _, _, _, _, _, _, _, _, _))

}

object GlobalSnapshotInfo {
  def empty = GlobalSnapshotInfo(
    SortedMap.empty,
    SortedMap.empty,
    SortedMap.empty,
    SortedMap.empty,
    SortedMap.empty,
    Some(SortedMap.empty),
    Some(SortedMap.empty),
    Some(SortedMap.empty),
    Some(SortedMap.empty),
    Some(SortedMap.empty),
    Some(SortedMap.empty),
    Some(SortedMap.empty),
    Some(SortedMap.empty),
    Some(SortedMap.empty),
    Some(SortedMap.empty)
  )

  implicit val optionAddressKeyEncoder: KeyEncoder[Option[Address]] = new KeyEncoder[Option[Address]] {
    def apply(key: Option[Address]): String = key match {
      case None          => KeyEncoder[String].apply("")
      case Some(address) => KeyEncoder[Address].apply(address)
    }
  }

  implicit val optionAddressKeyDecoder: KeyDecoder[Option[Address]] = new KeyDecoder[Option[Address]] {
    def apply(key: String): Option[Option[Address]] =
      if (key === "") Some(None) else KeyDecoder[Address].apply(key).map(_.some)
  }
}
