package io.constellationnetwork.schema

import cats.Parallel
import cats.effect.Async
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.delegatedStake.DelegatedStakeRecord
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.semver.SnapshotVersion
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.schema.transaction.TransactionReference
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.syntax.sortedCollection._

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object snapshot {

  trait StateProof

  trait FullSnapshot[P <: StateProof, SI <: SnapshotInfo[P]] extends Snapshot {
    val info: SI
  }

  trait IncrementalSnapshot[P <: StateProof] extends Snapshot {
    val stateProof: P
    val version: SnapshotVersion
  }

  trait Snapshot {
    val ordinal: SnapshotOrdinal
    val height: Height
    val subHeight: SubHeight
    val lastSnapshotHash: Hash
    val blocks: SortedSet[BlockAsActiveTip]
    val tips: SnapshotTips
    val epochProgress: EpochProgress

    def activeTips[F[_]: Async: Hasher]: F[SortedSet[ActiveTip]] =
      blocks.toList.traverse { blockAsActiveTip =>
        BlockReference
          .of(blockAsActiveTip.block)
          .map(blockRef => ActiveTip(blockRef, blockAsActiveTip.usageCount, ordinal))
      }.map(_.toSortedSet.union(tips.remainedActive))
  }

  /** Base trait for snapshot state information.
    *
    * State proof construction is decoupled from this trait. Use the `stateProofBuilder` factory methods in the companion objects of
    * concrete implementations (e.g., `GlobalSnapshotInfo.stateProofBuilder`, `CurrencySnapshotInfo.stateProofBuilder`).
    */
  trait SnapshotInfo[P <: StateProof] {
    val lastTxRefs: SortedMap[Address, TransactionReference]
    val balances: SortedMap[Address, Balance]

    def getActiveTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]] = SortedMap.empty
    def getActiveDelegatedStakes: SortedMap[Address, SortedSet[DelegatedStakeRecord]] = SortedMap.empty
  }

  @derive(encoder, decoder, show)
  case class SnapshotMetadata(
    ordinal: SnapshotOrdinal,
    hash: Hash,
    lastSnapshotHash: Hash,
    // Lets cheap consumers (e.g. the L1 alignment loop's
    // TooFarEpochProgress sync-check) read the epoch progress without pulling the
    // ~60 MB combined-snapshot body. Option for forward+backward wire compatibility:
    // older servers omit the field; older clients ignore it. Consumers should
    // always provide a fallback for `None` to handle mixed-version deployments.
    epochProgress: Option[EpochProgress] = None
  )

  @derive(decoder, encoder, order, show)
  case class MetagraphSyncDataInfo(
    globalOrdinalLastAcceptedOn: SnapshotOrdinal,
    globalEpochProgressLastAcceptedOn: EpochProgress,
    unappliedGlobalChangeOrdinals: SortedSet[SnapshotOrdinal]
  )
  object MetagraphSyncDataInfo {
    def empty: MetagraphSyncDataInfo = MetagraphSyncDataInfo(SnapshotOrdinal.MinValue, EpochProgress.MinValue, SortedSet.empty)
  }

}
