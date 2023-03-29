package org.tessellation.schema

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.semver.SnapshotVersion
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.security.hash.Hash
import org.tessellation.syntax.sortedCollection._

object snapshot {

  trait StateProof {
    val lastTxRefsProof: Hash
    val balancesProof: Hash
  }

  trait FullSnapshot[T <: Transaction, B <: Block[T], P <: StateProof, SI <: SnapshotInfo[P]] extends Snapshot[T, B] {
    val info: SI
  }

  trait IncrementalSnapshot[T <: Transaction, B <: Block[T], P <: StateProof] extends Snapshot[T, B] {
    val stateProof: P
    val version: SnapshotVersion
  }

  trait Snapshot[T <: Transaction, B <: Block[T]] {
    val ordinal: SnapshotOrdinal
    val height: Height
    val subHeight: SubHeight
    val lastSnapshotHash: Hash
    val blocks: SortedSet[BlockAsActiveTip[B]]
    val tips: SnapshotTips

    def activeTips[F[_]: Async: KryoSerializer]: F[SortedSet[ActiveTip]] =
      blocks.toList.traverse { blockAsActiveTip =>
        BlockReference
          .of(blockAsActiveTip.block)
          .map(blockRef => ActiveTip(blockRef, blockAsActiveTip.usageCount, ordinal))
      }.map(_.toSortedSet.union(tips.remainedActive))
  }

  trait SnapshotInfo[P <: StateProof] {
    val lastTxRefs: SortedMap[Address, TransactionReference]
    val balances: SortedMap[Address, Balance]

    def stateProof[F[_]: MonadThrow: KryoSerializer]: F[P]
  }

}
