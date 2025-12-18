package io.constellationnetwork.schema

import cats.Parallel
import cats.effect.{Async, Sync}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.json.StreamingJsonCodecs._
import io.constellationnetwork.merkletree.syntax._
import io.constellationnetwork.merkletree.{MerkleRoot, MerkleTree, Proof}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeRecord, PendingDelegatedStakeWithdrawal}
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.nodeCollateral.{NodeCollateralRecord, PendingNodeCollateralWithdrawal}
import io.constellationnetwork.schema.priceOracle.{PriceRecord, TokenPair}
import io.constellationnetwork.schema.snapshot.{MetagraphSyncDataInfo, SnapshotInfo, StateProof}
import io.constellationnetwork.schema.swap.{AllowSpend, AllowSpendReference}
import io.constellationnetwork.schema.tokenLock.{TokenLock, TokenLockReference}
import io.constellationnetwork.schema.transaction.TransactionReference
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.mpt.producer.StatefulMerklePatriciaProducer
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegInt
import io.circe._
import io.circe.disjunctionCodecs._
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.EncoderOps

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshotInfoV1(
  lastStateChannelSnapshotHashes: SortedMap[Address, Hash],
  lastTxRefs: SortedMap[Address, TransactionReference],
  balances: SortedMap[Address, Balance]
) extends SnapshotInfo[GlobalSnapshotStateProofV2] {
  def stateProof[F[_]: Parallel: Async: Hasher](ordinal: SnapshotOrdinal): F[GlobalSnapshotStateProofV2] = {
    val info = GlobalSnapshotInfoV1.toGlobalSnapshotInfo(this)
    info.lastCurrencySnapshots.merkleTree[F].flatMap { mt =>
      GlobalSnapshotInfo.legacyStateProof(info, mt)
    }
  }
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
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty)
    )
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
) extends SnapshotInfo[GlobalSnapshotStateProofV2] {
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
      None,
      None,
      None
    )

  def stateProof[F[_]: Parallel: Async: Hasher](ordinal: SnapshotOrdinal): F[GlobalSnapshotStateProofV2] =
    lastCurrencySnapshots.merkleTree[F].flatMap(stateProof(_))

  def stateProof[F[_]: Parallel: Async: Hasher](lastCurrencySnapshots: Option[MerkleTree]): F[GlobalSnapshotStateProofV2] =
    GlobalSnapshotInfo.legacyStateProof[F](toGlobalSnapshotInfo, lastCurrencySnapshots)
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
case class GlobalSnapshotInfoV3(
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
  activeDelegatedStakes: Option[SortedMap[Address, SortedSet[DelegatedStakeRecord]]],
  delegatedStakesWithdrawals: Option[SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]]],
  activeNodeCollaterals: Option[SortedMap[Address, SortedSet[NodeCollateralRecord]]],
  nodeCollateralWithdrawals: Option[SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]]],
  priceState: Option[SortedMap[TokenPair, PriceRecord]],
  metagraphSyncData: Option[SortedMap[Address, MetagraphSyncDataInfo]]
) extends SnapshotInfo[GlobalSnapshotStateProofV2] {
  def legacyStateProof[F[_]: Parallel: Async: Hasher](
    lastCurrencySnapshots: Option[MerkleTree]
  ): F[GlobalSnapshotStateProofV2] =
    GlobalSnapshotInfo.legacyStateProof[F](toGlobalSnapshotInfo, lastCurrencySnapshots)

  def stateProof[F[_]: Parallel: Async: Hasher](ordinal: SnapshotOrdinal): F[GlobalSnapshotStateProofV2] =
    lastCurrencySnapshots.merkleTree[F].flatMap(legacyStateProof(_))

  def toGlobalSnapshotInfo: GlobalSnapshotInfo =
    GlobalSnapshotInfo(
      lastStateChannelSnapshotHashes,
      lastTxRefs,
      balances,
      lastCurrencySnapshots,
      lastCurrencySnapshotsProofs,
      activeAllowSpends,
      activeTokenLocks,
      tokenLockBalances,
      lastAllowSpendRefs,
      lastTokenLockRefs,
      updateNodeParameters,
      activeDelegatedStakes,
      delegatedStakesWithdrawals,
      activeNodeCollaterals,
      nodeCollateralWithdrawals,
      priceState,
      metagraphSyncData
    )

  override def getActiveTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]] = activeTokenLocks.getOrElse(SortedMap.empty)

  override def getActiveDelegatedStakes: SortedMap[Address, SortedSet[DelegatedStakeRecord]] =
    activeDelegatedStakes.getOrElse(SortedMap.empty)
}

object GlobalSnapshotInfoV3 {
  import GlobalSnapshotInfo.{optionAddressKeyDecoder, optionAddressKeyEncoder}

  def fromGlobalSnapshotInfo(gs: GlobalSnapshotInfo): GlobalSnapshotInfoV3 =
    GlobalSnapshotInfoV3(
      gs.lastStateChannelSnapshotHashes,
      gs.lastTxRefs,
      gs.balances,
      gs.lastCurrencySnapshots,
      gs.lastCurrencySnapshotsProofs,
      gs.activeAllowSpends,
      gs.activeTokenLocks,
      gs.tokenLockBalances,
      gs.lastAllowSpendRefs,
      gs.lastTokenLockRefs,
      gs.updateNodeParameters,
      gs.activeDelegatedStakes,
      gs.delegatedStakesWithdrawals,
      gs.activeNodeCollaterals,
      gs.nodeCollateralWithdrawals,
      gs.priceState,
      gs.metagraphSyncData
    )
}

@derive(decoder, eqv, show)
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
  activeDelegatedStakes: Option[SortedMap[Address, SortedSet[DelegatedStakeRecord]]],
  delegatedStakesWithdrawals: Option[SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]]],
  activeNodeCollaterals: Option[SortedMap[Address, SortedSet[NodeCollateralRecord]]],
  nodeCollateralWithdrawals: Option[SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]]],
  priceState: Option[SortedMap[TokenPair, PriceRecord]],
  metagraphSyncData: Option[SortedMap[Address, MetagraphSyncDataInfo]]
) extends SnapshotInfo[GlobalSnapshotStateProof] {

  def stateProof[F[_]: Parallel: Async: Hasher](ordinal: SnapshotOrdinal): F[GlobalSnapshotStateProof] =
    this.allStateEntries.buildMpt.map(mptRoot => GlobalSnapshotStateProof(mptRoot.value))

  def stateProofFor[F[_]: Parallel: Async: Hasher](
    hashScheme: HashLogic,
    ordinal: SnapshotOrdinal
  ): F[GlobalSnapshotStateProof] = hashScheme match {
    case JsonHash => stateProof(ordinal)
    case KryoHash =>
      lastCurrencySnapshots
        .merkleTree[F]
        .flatMap(mt => GlobalSnapshotInfo.legacyStateProof(this, mt))
        .map(GlobalSnapshotStateProof.fromLegacyProof)
  }

  def stateProof[F[_]: Parallel: Sync: Hasher](statefulMPTProducer: StatefulMerklePatriciaProducer[F]): F[GlobalSnapshotStateProof] =
    statefulMPTProducer.build.flatMap {
      case Left(value)  => (new Throwable(s"Error when building MPT. Message: ${value.getMessage}")).raiseError[F, GlobalSnapshotStateProof]
      case Right(value) => GlobalSnapshotStateProof(value.rootHash.value).pure
    }

  override def getActiveTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]] = activeTokenLocks.getOrElse(SortedMap.empty)

  override def getActiveDelegatedStakes: SortedMap[Address, SortedSet[DelegatedStakeRecord]] =
    activeDelegatedStakes.getOrElse(SortedMap.empty)
}

object GlobalSnapshotInfo {
  implicit val encoder: Encoder[GlobalSnapshotInfo] = deriveEncoder

  def legacyStateProof[F[_]: Parallel: Sync: Hasher](
    info: GlobalSnapshotInfo,
    lastCurrencySnapshots: Option[MerkleTree]
  ): F[GlobalSnapshotStateProofV2] =
    (
      info.lastStateChannelSnapshotHashes.hash,
      info.lastTxRefs.hash,
      info.balances.hash,
      info.activeAllowSpends.traverse(_.hash),
      info.activeTokenLocks.traverse(_.hash),
      info.tokenLockBalances.traverse(_.hash),
      info.lastAllowSpendRefs.traverse(_.hash),
      info.lastTokenLockRefs.traverse(_.hash),
      info.updateNodeParameters.traverse(_.hash),
      info.activeDelegatedStakes.traverse(_.hash),
      info.delegatedStakesWithdrawals.traverse(_.hash),
      info.activeNodeCollaterals.traverse(_.hash),
      info.nodeCollateralWithdrawals.traverse(_.hash),
      info.priceState.traverse(_.hash),
      info.metagraphSyncData.traverse(_.hash)
    ).mapN(GlobalSnapshotStateProofV2.apply(_, _, _, lastCurrencySnapshots.map(_.getRoot), _, _, _, _, _, _, _, _, _, _, _, _))

  def empty: GlobalSnapshotInfo = GlobalSnapshotInfo(
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
