package org.tessellation.schema

import scala.collection.immutable.SortedSet

import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.hash.Hash

object snapshot {

  trait Snapshot[T <: Transaction, B <: Block[T]] {
    val ordinal: SnapshotOrdinal
    val height: Height
    val subHeight: SubHeight
    val lastSnapshotHash: Hash
    val blocks: SortedSet[BlockAsActiveTip[B]]
    val tips: SnapshotTips
  }

}
