package io.constellationnetwork.dag.l0.infrastructure.mempool

import scala.collection.immutable.SortedMap
import scala.concurrent.duration._

import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.transaction.TransactionReference
import io.constellationnetwork.security.signature.Signed

final case class DagAwaitingParentConfig(
  ttl: FiniteDuration,
  maxParentOrdinalGap: Long,
  maxAwaitingParentTxs: Int,
  maxAwaitingParentPerAddress: Int
)

object DagAwaitingParentConfig {
  val default: DagAwaitingParentConfig =
    DagAwaitingParentConfig(
      ttl = 10.minutes,
      maxParentOrdinalGap = 32L,
      maxAwaitingParentTxs = 5000,
      maxAwaitingParentPerAddress = 32
    )
}

final case class DagAwaitingParentStatus(
  awaitingParent: Boolean,
  currentLastTxOrdinal: Long,
  maxParentOrdinal: Long,
  maxParentOrdinalGap: Long,
  addressCount: Int
)

object DagAwaitingParent {

  def status(
    block: Signed[Block],
    lastTxRefs: SortedMap[Address, TransactionReference]
  ): DagAwaitingParentStatus = {
    val bySource = block.value.transactions.toNonEmptyList
      .groupBy(_.value.source)
      .toList
      .map { case (address, txs) => address -> txs.sortBy(_.ordinal).toList }

    val inspected = bySource.map {
      case (address, txChain) =>
        val lastOrdinal = lastTxRefs.getOrElse(address, TransactionReference.empty).ordinal.value.value
        val parentOrdinal = txChain.head.value.parent.ordinal.value.value
        val gap = math.max(0L, parentOrdinal - lastOrdinal)

        (lastOrdinal, parentOrdinal, gap)
    }

    val maxGap = inspected.map(_._3).maxOption.getOrElse(0L)

    DagAwaitingParentStatus(
      awaitingParent = maxGap > 0L,
      currentLastTxOrdinal = inspected.map(_._1).maxOption.getOrElse(0L),
      maxParentOrdinal = inspected.map(_._2).maxOption.getOrElse(0L),
      maxParentOrdinalGap = maxGap,
      addressCount = bySource.size
    )
  }
}
