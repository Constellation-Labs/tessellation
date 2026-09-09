package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.data.NonEmptySet

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.domain.block.generators.signedBlockGen
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.transaction.{TransactionOrdinal, TransactionReference}
import io.constellationnetwork.security.hash.Hash

import eu.timepit.refined.types.numeric.NonNegLong
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object DagAwaitingParentQueueSuite extends SimpleIOSuite with Checkers {

  // Two distinct, well-formed 64-hex-char hashes
  private val committedHash = Hash(Hash.empty.value.init + "1")
  private val forkHash = Hash(Hash.empty.value.init + "2")
  private val committedOrdinal = 5L

  private def ordinal(value: Long): TransactionOrdinal = TransactionOrdinal(NonNegLong.unsafeFrom(value))

  // isPermanentlyDead judges a block dead when ANY source tx-chain head attaches at-or-before a committed
  // position: parent ordinal strictly below the committed last, or equal with a different hash (a lost fork).
  // Parent above committed is merely awaiting; equal-with-matching-hash is acceptable now.
  test("isPermanentlyDead classifies a DAG block against committed lastTxRefs by parent position") {
    forall(signedBlockGen) { baseBlock =>
      val baseTx = baseBlock.value.transactions.head
      val source = baseTx.value.source

      def blockWithParent(parentOrdinal: Long, parentHash: Hash) = {
        val tx = baseTx.copy(
          value = baseTx.value.copy(
            source = source,
            parent = TransactionReference(ordinal(parentOrdinal), parentHash)
          )
        )
        baseBlock.copy(value = baseBlock.value.copy(transactions = NonEmptySet.one(tx)))
      }

      val lastTxRefs: SortedMap[Address, TransactionReference] =
        SortedMap(source -> TransactionReference(ordinal(committedOrdinal), committedHash))

      val below = DagAwaitingParentQueue.isPermanentlyDead(blockWithParent(committedOrdinal - 1, committedHash), lastTxRefs)
      val equalMatchingHash = DagAwaitingParentQueue.isPermanentlyDead(blockWithParent(committedOrdinal, committedHash), lastTxRefs)
      val equalForkedHash = DagAwaitingParentQueue.isPermanentlyDead(blockWithParent(committedOrdinal, forkHash), lastTxRefs)
      val above = DagAwaitingParentQueue.isPermanentlyDead(blockWithParent(committedOrdinal + 1, committedHash), lastTxRefs)
      val freshAccount = DagAwaitingParentQueue.isPermanentlyDead(blockWithParent(0L, Hash.empty), SortedMap.empty)

      expect(below, "parent ordinal below committed last is permanently dead")
        .and(expect(!equalMatchingHash, "parent equal to committed last with matching hash is acceptable, not dead"))
        .and(expect(equalForkedHash, "parent equal to committed last with a different hash (lost fork) is permanently dead"))
        .and(expect(!above, "parent above committed last is awaiting, not dead"))
        .and(expect(!freshAccount, "first transaction of an unseen source (parent ordinal 0, empty hash) is not dead"))
    }
  }
}
