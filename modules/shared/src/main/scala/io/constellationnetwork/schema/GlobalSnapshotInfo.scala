package io.constellationnetwork.schema

import cats.effect.{Async, Sync}
import cats.syntax.all._
import cats.{MonadThrow, Parallel}

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.json.StreamingJsonCodecs._
import io.constellationnetwork.merkletree.syntax._
import io.constellationnetwork.merkletree.{MerkleRoot, MerkleTree, Proof}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeRecord, PendingDelegatedStakeWithdrawal}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.GlobalStateFieldId
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.nodeCollateral.{NodeCollateralRecord, PendingNodeCollateralWithdrawal}
import io.constellationnetwork.schema.priceOracle.{PriceRecord, TokenPair}
import io.constellationnetwork.schema.snapshot.{MetagraphSyncDataInfo, SnapshotInfo, StateProof}
import io.constellationnetwork.schema.stateproof.StateProofBuilder
import io.constellationnetwork.schema.swap.{AllowSpend, AllowSpendReference}
import io.constellationnetwork.schema.tokenLock.{TokenLock, TokenLockReference}
import io.constellationnetwork.schema.transaction.TransactionReference
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.mpt.MerklePatriciaTrie
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
) extends SnapshotInfo[GlobalSnapshotStateProofV1] {
  def toGlobalSnapshotInfo: GlobalSnapshotInfo =
    GlobalSnapshotInfo(
      lastStateChannelSnapshotHashes,
      lastTxRefs,
      balances,
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
      Some(SortedMap.empty),
      Some(SortedMap.empty)
    )

  def stateProof[F[_]: Parallel: Async: Hasher: JsonSerializer](ordinal: SnapshotOrdinal)(
    implicit stateProofSelector: StateProofSelector
  ): F[GlobalSnapshotStateProofV1] =
    GlobalSnapshotInfo
      .legacyStateProof[F](toGlobalSnapshotInfo, None)
      .map(GlobalSnapshotStateProofV1.fromGlobalSnapshotStateProof)
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
      None,
      None,
      None,
      None
    )

  def stateProof[F[_]: Parallel: Async: Hasher: JsonSerializer](ordinal: SnapshotOrdinal)(
    implicit stateProofSelector: StateProofSelector
  ): F[GlobalSnapshotStateProof] =
    lastCurrencySnapshots.merkleTree[F].flatMap(stateProof(_))

  def stateProof[F[_]: Parallel: Async: Hasher](lastCurrencySnapshots: Option[MerkleTree]): F[GlobalSnapshotStateProof] =
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
  metagraphSyncData: Option[SortedMap[Address, MetagraphSyncDataInfo]],
  // Allow-spend references the global layer has already settled, keyed by currency then by the address the
  // allow-spend belongs to, mirroring `activeAllowSpends`. The value is the allow-spend's lastValidEpochProgress,
  // which is what later lets the entry be dropped. See AllowSpendStateManager (PROT-1691).
  retiredAllowSpendRefs: Option[SortedMap[Option[Address], SortedMap[Address, SortedMap[Hash, EpochProgress]]]]
) extends SnapshotInfo[GlobalSnapshotStateProof] {

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
      metagraphSyncData,
      retiredAllowSpendRefs
    )

  def stateProof[F[_]: Parallel: Async: Hasher: JsonSerializer](ordinal: SnapshotOrdinal)(
    implicit stateProofSelector: StateProofSelector
  ): F[GlobalSnapshotStateProof] =
    stateProofSelector.select(ordinal) match {
      case LegacyFormat         => lastCurrencySnapshots.merkleTree[F].flatMap(stateProof(_))
      case MerklePatriciaFormat => GlobalSnapshotInfo.mptStateProof[F](this, ordinal)
    }

  def stateProof[F[_]: Parallel: Async: Hasher](lastCurrencySnapshots: Option[MerkleTree]): F[GlobalSnapshotStateProof] =
    GlobalSnapshotInfo.legacyStateProof[F](toGlobalSnapshotInfo, lastCurrencySnapshots)

  override def getActiveTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]] = activeTokenLocks.getOrElse(SortedMap.empty)

  override def getActiveDelegatedStakes: SortedMap[Address, SortedSet[DelegatedStakeRecord]] =
    activeDelegatedStakes.getOrElse(SortedMap.empty)
}

object GlobalSnapshotInfo {

  /** Creates a StateProofBuilder for GlobalSnapshotInfo (no producer).
    *
    * Uses legacy Merkle trees for pre-MPT ordinals, rebuilds MPT from state for post-migration ordinals. For efficient proof building with
    * a pre-built trie, use `stateProofBuilder(Some(producer))`.
    */
  def stateProofBuilder[F[_]: Async: Parallel: JsonSerializer](
    implicit selector: GlobalStateProofSelector
  ): StateProofBuilder[F, GlobalSnapshotInfo, GlobalSnapshotStateProof] =
    stateProofBuilder(None)

  /** Creates a StateProofBuilder with optional MPT producer.
    *
    * @param producer
    *   Optional MPT producer for efficient proof building from pre-built trie. When None, MPT proofs are rebuilt from snapshot state.
    */
  def stateProofBuilder[F[_]: Async: Parallel: JsonSerializer](
    producer: Option[StatefulMerklePatriciaProducer[F]]
  )(implicit selector: GlobalStateProofSelector): StateProofBuilder[F, GlobalSnapshotInfo, GlobalSnapshotStateProof] =
    StateProofBuilder.instance { (info, ordinal, hasher) =>
      implicit val h: Hasher[F] = hasher
      implicit val s: StateProofSelector = selector
      selector.select(ordinal) match {
        case LegacyFormat =>
          info.lastCurrencySnapshots.merkleTree[F].flatMap { lastCurrencySnapshotsMerkle =>
            legacyStateProof[F](info, lastCurrencySnapshotsMerkle)
          }
        case MerklePatriciaFormat =>
          producer match {
            case Some(p) =>
              p.buildForOrdinal(ordinal).flatMap {
                case Left(err) => err.raiseError[F, GlobalSnapshotStateProof]
                case Right(_) =>
                  p.getRootHashForOrdinal(ordinal).flatMap {
                    // mptRoot comes from the pre-built producer trie (leader path); the per-field sub-trie roots
                    // (when activated) are derived from `info`, identical to the validator's rebuild path below.
                    case Some(value) => assembleMptProof(info, ordinal, value.value)
                    case None        => MonadThrow[F].raiseError(new RuntimeException(s"Could not get mptRootHash for ordinal $ordinal"))
                  }
              }
            case None =>
              mptStateProof[F](info, ordinal)
          }
      }
    }

  def mptStateProof[F[_]: Parallel: Async: Hasher: JsonSerializer](info: GlobalSnapshotInfo, ordinal: SnapshotOrdinal)(
    implicit stateProofSelector: StateProofSelector
  ): F[GlobalSnapshotStateProof] =
    info.allStateEntries.buildMpt.flatMap(mptRoot => assembleMptProof(info, ordinal, mptRoot.value))

  /** Parallelism bound for building the per-field sub-tries (#10). Kept small: each sub-trie build over a large field (e.g. balances) is
    * memory-heavy, so it runs on the signed snapshot hot path bounded, and only when sub-trie roots are activated for the ordinal.
    */
  private val subTrieRootParallelism: Int = 4

  /** Per-`GlobalStateFieldId` Merkle root, built from the SAME merged state-entry map that produces `mptRoot`, grouped by field id -- so
    * there is no drift between what is hashed into `mptRoot` and what is hashed per field.
    */
  def subTrieRoots[F[_]: Parallel: Async: Hasher: JsonSerializer](
    info: GlobalSnapshotInfo
  )(implicit stateProofSelector: StateProofSelector): F[Map[GlobalStateFieldId, Hash]] =
    info.allStateEntries.flatMap { all =>
      // Bounded concurrency without cats-effect's parTraverseN: build the per-field sub-tries in chunks of
      // subTrieRootParallelism (parallel within a chunk, chunks sequential). A single large field (e.g.
      // balances) is memory-heavy, so the cap keeps the signed hot path bounded.
      all.groupBy { case (k, _) => k.fieldId }.toList
        .grouped(subTrieRootParallelism)
        .toList
        .flatTraverse(_.parTraverse { case (fieldId, entries) => entries.pure[F].buildMpt.map(root => fieldId -> root.value) })
        .map(_.toMap)
    }

  /** Assemble the MPT-format state proof from the merged `mptRoot`. When sub-trie roots are activated for `ordinal` (see
    * `StateProofSelector.subTrieRootsEnabled`, default inert), the otherwise-`None` fields carry their per-`GlobalStateFieldId` sub-trie
    * root so a divergence can be localized to a field. Both proof paths (the leader's pre-built producer trie and the validator's rebuild)
    * supply the same merged `mptRoot`, and the per-field roots are a pure function of `info`, so both yield identical SIGNED proofs.
    * `lastCurrencySnapshotsProof` is intentionally left `None` (its type is `Option[MerkleRoot]`, not `Option[Hash]`).
    */
  def assembleMptProof[F[_]: Parallel: Async: Hasher: JsonSerializer](
    info: GlobalSnapshotInfo,
    ordinal: SnapshotOrdinal,
    mptRoot: Hash
  )(implicit stateProofSelector: StateProofSelector): F[GlobalSnapshotStateProof] =
    if (stateProofSelector.subTrieRootsEnabled(ordinal))
      subTrieRoots(info).map { roots =>
        // Qualified field ids (not a wildcard import) so GlobalStateFieldId.UpdateNodeParameters does not
        // collide with the node.UpdateNodeParameters imported at file scope.
        GlobalSnapshotStateProof(
          roots.getOrElse(GlobalStateFieldId.LastStateChannelSnapshotHashes, Hash.empty),
          roots.getOrElse(GlobalStateFieldId.LastTxRefs, Hash.empty),
          roots.getOrElse(GlobalStateFieldId.Balances, Hash.empty),
          None,
          roots.get(GlobalStateFieldId.ActiveAllowSpends),
          roots.get(GlobalStateFieldId.ActiveTokenLocks),
          roots.get(GlobalStateFieldId.TokenLockBalances),
          roots.get(GlobalStateFieldId.LastAllowSpendRefs),
          roots.get(GlobalStateFieldId.LastTokenLockRefs),
          roots.get(GlobalStateFieldId.UpdateNodeParameters),
          roots.get(GlobalStateFieldId.ActiveDelegatedStakes),
          roots.get(GlobalStateFieldId.DelegatedStakesWithdrawals),
          roots.get(GlobalStateFieldId.ActiveNodeCollaterals),
          roots.get(GlobalStateFieldId.NodeCollateralWithdrawals),
          roots.get(GlobalStateFieldId.PriceState),
          roots.get(GlobalStateFieldId.MetagraphSyncData),
          roots.get(GlobalStateFieldId.RetiredAllowSpendRefs),
          Some(mptRoot)
        )
      }
    else
      GlobalSnapshotStateProof(
        Hash.empty,
        Hash.empty,
        Hash.empty,
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
        None,
        None,
        None,
        Some(mptRoot)
      ).pure[F]

  def legacyStateProof[F[_]: Parallel: Sync: Hasher](
    info: GlobalSnapshotInfo,
    lastCurrencySnapshots: Option[MerkleTree]
  ): F[GlobalSnapshotStateProof] =
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
      info.metagraphSyncData.traverse(_.hash),
      info.retiredAllowSpendRefs.traverse(_.hash)
    ).mapN(GlobalSnapshotStateProof.apply(_, _, _, lastCurrencySnapshots.map(_.getRoot), _, _, _, _, _, _, _, _, _, _, _, _, _, None))

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
    Some(SortedMap.empty),
    Some(SortedMap.empty)
  )

  implicit val hashKeyEncoder: KeyEncoder[Hash] = KeyEncoder[String].contramap(_.value)

  implicit val hashKeyDecoder: KeyDecoder[Hash] = KeyDecoder[String].map(Hash(_))

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
