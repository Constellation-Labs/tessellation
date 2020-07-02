package org.tessellation

import org.scalacheck.Properties
import org.tessellation.BlockTest.property
import org.tessellation.schema.{Block, Signature, Snapshot, Transaction}
import org.scalacheck.Prop.{forAll, _}

object SnapshotTest extends Properties("SnapshotTest") {
  property("combine") = {
    val tx1 = Transaction(1L)
    val tx2 = Transaction(2L)
    val tx3 = Transaction(3L)
    val tx4 = Transaction(4L)

    val block1 = Block(List(tx1, tx2))
    val block2 = Block(List(tx3, tx4))

    val snapshot1 = Snapshot(List(block1))
    val snapshot2 = Snapshot(List(block2))

    val merged = snapshot1.combine(snapshot1, snapshot2)
    val unified = Snapshot(List(block1, block2))

    merged ?= unified
  }
}
