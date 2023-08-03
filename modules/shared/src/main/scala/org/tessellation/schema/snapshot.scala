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
import org.tessellation.schema.transaction.TransactionReference
import org.tessellation.security.hash.Hash
import org.tessellation.syntax.sortedCollection._

import derevo.cats.show
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

  @derive(encoder, decoder, show)
  case class SnapshotMetadata(ordinal: SnapshotOrdinal, hash: Hash, lastSnapshotHash: Hash)

}
