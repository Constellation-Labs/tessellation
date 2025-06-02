package io.constellationnetwork.schema

import cats.Parallel
import cats.effect.Async
import cats.effect.kernel.Sync
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.semver.SnapshotVersion
import io.constellationnetwork.schema.transaction.TransactionReference
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.syntax.sortedCollection._

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object snapshot {

  trait StateProof {
    val lastTxRefsProof: Hash
    val balancesProof: Hash
  }

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

  trait SnapshotInfo[P <: StateProof] {
    val lastTxRefs: SortedMap[Address, TransactionReference]
    val balances: SortedMap[Address, Balance]

    def stateProof[F[_]: Parallel: Sync: Hasher](ordinal: SnapshotOrdinal): F[P]
  }

  @derive(encoder, decoder, show)
  case class SnapshotMetadata(ordinal: SnapshotOrdinal, hash: Hash, lastSnapshotHash: Hash)

  @derive(decoder, encoder, order, show)
  case class GlobalSnapshotWithCurrencyInfo(
    ordinal: SnapshotOrdinal,
    epochProgress: EpochProgress
  )

}
