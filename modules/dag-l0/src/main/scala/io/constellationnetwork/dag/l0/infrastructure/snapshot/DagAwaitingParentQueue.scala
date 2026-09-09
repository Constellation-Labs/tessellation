package io.constellationnetwork.dag.l0.infrastructure.snapshot

import java.time.{Duration, Instant}

import cats.Monad
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.dag.l0.infrastructure.mempool.{DagAwaitingParent, DagAwaitingParentConfig, DagAwaitingParentStatus}
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.{DAGEvent, GlobalSnapshotEvent}
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.schema.transaction.TransactionReference
import io.constellationnetwork.schema.{Block, GlobalSnapshotInfo}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import org.typelevel.log4cats.SelfAwareStructuredLogger

private[snapshot] object DagAwaitingParentQueue {

  private val outcomeLabel = Metrics.unsafeLabelName("outcome")
  private val stageLabel = Metrics.unsafeLabelName("stage")

  final case class DrainResult(
    candidates: Int,
    reactivated: Int,
    expired: Int,
    rejectedGapTooLarge: Int,
    rejectedBacklogFull: Int,
    backlogAfter: Int,
    maxGapAfter: Long
  )

  private final case class Entry(
    hash: Hash,
    receivedAt: Instant,
    event: DAGEvent,
    status: DagAwaitingParentStatus
  )

  private final case class DrainState(
    selected: List[Entry],
    pending: List[Entry],
    projectedRefs: SortedMap[Address, TransactionReference]
  )

  def maintain[F[_]: Async: Metrics: Hasher](
    eventMempool: EventMempool[F, GlobalSnapshotEvent, GlobalStateKey],
    context: GlobalSnapshotInfo,
    config: DagAwaitingParentConfig,
    maxReactivate: Int,
    logger: SelfAwareStructuredLogger[F]
  ): F[DrainResult] =
    for {
      now <- Async[F].realTimeInstant
      suspended <- eventMempool.suspendedSnapshot(config.maxAwaitingParentTxs + 1024)
      dagEntries = suspended.entries.toList.collect {
        case (hash, entry) if entry.hashed.signed.value.isInstanceOf[DAGEvent] =>
          val event = entry.hashed.signed.value.asInstanceOf[DAGEvent]
          Entry(hash, entry.receivedAt, event, DagAwaitingParent.status(event.value, context.lastTxRefs))
      }

      expired = dagEntries.collect {
        case entry if Duration.between(entry.receivedAt, now).toMillis > config.ttl.toMillis => entry.hash
      }.toSet
      gapTooLarge = dagEntries.collect {
        case entry if entry.status.maxParentOrdinalGap > config.maxParentOrdinalGap => entry.hash
      }.toSet
      available = dagEntries.filterNot(entry => expired.contains(entry.hash) || gapTooLarge.contains(entry.hash))
      drain <- selectDrainable(available, context.lastTxRefs, maxReactivate)
      eligible = drain.map(_.hash).toSet
      remaining = available.filterNot(entry => eligible.contains(entry.hash))
      overflow = remaining.sortBy(_.receivedAt).drop(config.maxAwaitingParentTxs).map(_.hash).toSet
      perAddressOverflow = perAddressOverflowHashes(remaining, config.maxAwaitingParentPerAddress)
      rejected = gapTooLarge ++ overflow ++ perAddressOverflow
      toRemove = expired ++ rejected
      retained = remaining.filterNot(entry => toRemove.contains(entry.hash))
      backlogAfter = retained.size
      maxGapAfter = retained.map(_.status.maxParentOrdinalGap).maxOption.getOrElse(0L)

      _ <- eventMempool.reactivate(eligible).whenA(eligible.nonEmpty)
      _ <- eventMempool.remove(toRemove).whenA(toRemove.nonEmpty)
      _ <- recordMetrics(
        candidates = available.size,
        reactivated = eligible.size,
        expired = expired.size,
        gapTooLarge = gapTooLarge.size,
        backlogFull = (overflow ++ perAddressOverflow).size,
        backlogAfter = backlogAfter,
        maxGapAfter = maxGapAfter
      )
      _ <- logger
        .info(
          s"[DAG_AWAITING_PARENT_DRAIN] candidates=${available.size} reactivated=${eligible.size} " +
            s"expired=${expired.size} rejectedGapTooLarge=${gapTooLarge.size} " +
            s"rejectedBacklogFull=${(overflow ++ perAddressOverflow).size} backlogAfter=$backlogAfter maxGapAfter=$maxGapAfter"
        )
        .whenA(available.nonEmpty || eligible.nonEmpty || toRemove.nonEmpty)
    } yield
      DrainResult(
        candidates = available.size,
        reactivated = eligible.size,
        expired = expired.size,
        rejectedGapTooLarge = gapTooLarge.size,
        rejectedBacklogFull = (overflow ++ perAddressOverflow).size,
        backlogAfter = backlogAfter,
        maxGapAfter = maxGapAfter
      )

  // Permanently-dead DAG blocks accumulate in the ACTIVE event mempool and degrade consensus cadence under
  // double-spend / double-use load. A block whose per-source tx chain attaches at or before a position already
  // committed for that source (a conflicting tx won that slot in a PRIOR snapshot) is rejected by block
  // acceptance every round, but -- unlike an awaiting-parent block, which gets suspended -- it is never suspended,
  // and since events are cleared only when they enter a committed artifact it is never removed either. It is then
  // re-validated and re-rejected on every proposal build forever, growing proposal cost. Evict such blocks here,
  // judged against the COMMITTED `lastTxRefs` only.
  //
  // Safety: this MUST use the committed (finalized) `context.lastTxRefs`, not a single proposal's acceptance
  // result. A losing proposal's rejection can be a winning proposal's acceptance when a double-spend is resolved
  // WITHIN a round; only a conflict already committed in a PRIOR snapshot is provably dead. `lastTxRefs` is
  // immutable per committed position and only advances, so a block judged dead here can never later become
  // valid -- eviction is monotonically safe.
  def evictPermanentlyRejected[F[_]: Async: Metrics](
    eventMempool: EventMempool[F, GlobalSnapshotEvent, GlobalStateKey],
    context: GlobalSnapshotInfo,
    logger: SelfAwareStructuredLogger[F]
  ): F[Int] =
    for {
      activeHashes <- eventMempool.getEventHashes
      active <- eventMempool.getMultiple(activeHashes)
      dead = active.collect {
        case (hash, hashed) =>
          hashed.signed.value match {
            case dagEvent: DAGEvent if isPermanentlyDead(dagEvent.value, context.lastTxRefs) => hash.some
            case _                                                                           => none[Hash]
          }
      }.flatten.toSet
      _ <- eventMempool.remove(dead).whenA(dead.nonEmpty)
      _ <- Metrics[F]
        .incrementCounterBy("dag_global_snapshot_dag_tx_permanently_rejected_evicted_total", dead.size.toLong)
        .whenA(dead.nonEmpty)
      _ <- logger
        .info(s"[DAG_DEAD_BLOCK_EVICT] evicted=${dead.size} active=${active.size}")
        .whenA(dead.nonEmpty)
    } yield dead.size

  // A DAG block is permanently dead when ANY of its per-source tx chains attaches behind the committed chain:
  // the head (lowest-ordinal) tx's parent ordinal is strictly below the committed last, or equal to it with a
  // different hash (a lost fork). Parent ordinal ABOVE committed last is merely awaiting (handled by suspend +
  // `maintain`); equal-with-matching-hash is acceptable now. Blocks accept atomically, so one dead source chain
  // kills the whole block.
  private[snapshot] def isPermanentlyDead(
    block: Signed[Block],
    lastTxRefs: SortedMap[Address, TransactionReference]
  ): Boolean =
    block.value.transactions.toNonEmptyList
      .groupBy(_.value.source)
      .exists {
        case (address, txs) =>
          val head = txs.sortBy(_.ordinal).head
          val lastRef = lastTxRefs.getOrElse(address, TransactionReference.empty)
          val parentOrdinal = head.value.parent.ordinal.value.value
          val lastOrdinal = lastRef.ordinal.value.value

          parentOrdinal < lastOrdinal ||
          (parentOrdinal === lastOrdinal && head.value.parent.hash =!= lastRef.hash)
      }

  private def selectDrainable[F[_]: Async: Hasher](
    entries: List[Entry],
    lastTxRefs: SortedMap[Address, TransactionReference],
    maxReactivate: Int
  ): F[List[Entry]] = {
    def loop(state: DrainState): F[DrainState] =
      if (state.pending.isEmpty || state.selected.size >= maxReactivate) state.pure[F]
      else
        state.pending
          .foldLeftM((List.empty[Entry], List.empty[Entry], state.projectedRefs, false, state.selected)) {
            case ((remaining, acceptedThisPass, refs, changed, selected), entry) =>
              if (selected.size + acceptedThisPass.size >= maxReactivate)
                (entry :: remaining, acceptedThisPass, refs, changed, selected).pure[F]
              else if (!entry.status.awaitingParent)
                projectIfEligible(entry.event.value, refs).map {
                  case Some(nextRefs) => (remaining, entry :: acceptedThisPass, nextRefs, true, selected)
                  case None           => (remaining, entry :: acceptedThisPass, refs, true, selected)
                }
              else
                projectIfEligible(entry.event.value, refs).map {
                  case Some(nextRefs) => (remaining, entry :: acceptedThisPass, nextRefs, true, selected)
                  case None           => (entry :: remaining, acceptedThisPass, refs, changed, selected)
                }
          }
          .flatMap {
            case (remaining, acceptedThisPass, refs, true, selected) =>
              loop(DrainState(selected ++ acceptedThisPass.reverse, remaining.reverse, refs))
            case (_, _, _, false, _) =>
              state.pure[F]
          }

    loop(DrainState(Nil, entries.sortBy(_.hash.show), lastTxRefs)).map(_.selected)
  }

  private def projectIfEligible[F[_]: Async: Hasher](
    block: Signed[Block],
    projectedRefs: SortedMap[Address, TransactionReference]
  ): F[Option[SortedMap[Address, TransactionReference]]] = {
    val bySource = block.value.transactions.toNonEmptyList
      .groupBy(_.value.source)
      .toList
      .map { case (address, txs) => address -> txs.sortBy(_.ordinal).toList }

    bySource
      .foldLeftM(projectedRefs.some) {
        case (None, _) => none[SortedMap[Address, TransactionReference]].pure[F]
        case (Some(refs), (address, txChain)) =>
          val lastTxRef = refs.getOrElse(address, TransactionReference.empty)
          val head = txChain.head
          val parentOrdinal = head.value.parent.ordinal.value.value
          val lastOrdinal = lastTxRef.ordinal.value.value

          if (parentOrdinal === lastOrdinal && head.value.parent.hash === lastTxRef.hash)
            TransactionReference.of(txChain.last).map(lastRef => refs.updated(address, lastRef).some)
          else none[SortedMap[Address, TransactionReference]].pure[F]
      }
  }

  private def perAddressOverflowHashes(entries: List[Entry], maxPerAddress: Int): Set[Hash] = {
    val addressToEntries = entries.flatMap { entry =>
      entry.event.value.value.transactions.toNonEmptyList
        .map(_.value.source)
        .toList
        .distinct
        .map(_ -> (entry.hash, entry.receivedAt))
    }.groupMap(_._1)(_._2)

    addressToEntries.values.toList
      .flatMap(_.sortBy(_._2).drop(maxPerAddress).map(_._1))
      .toSet
  }

  private def recordMetrics[F[_]: Monad: Metrics](
    candidates: Int,
    reactivated: Int,
    expired: Int,
    gapTooLarge: Int,
    backlogFull: Int,
    backlogAfter: Int,
    maxGapAfter: Long
  ): F[Unit] =
    Metrics[F].updateGauge("dag_global_snapshot_dag_tx_awaiting_parent_backlog", backlogAfter.toLong) >>
      Metrics[F].updateGauge("dag_global_snapshot_dag_tx_awaiting_parent_max_gap", maxGapAfter) >>
      Metrics[F].updateGauge(
        "dag_global_snapshot_dag_tx_awaiting_parent_drain_count",
        candidates.toLong,
        Seq(stageLabel -> "candidate")
      ) >>
      Metrics[F].updateGauge(
        "dag_global_snapshot_dag_tx_awaiting_parent_drain_count",
        reactivated.toLong,
        Seq(stageLabel -> "reactivated")
      ) >>
      Metrics[F].updateGauge(
        "dag_global_snapshot_dag_tx_awaiting_parent_drain_count",
        backlogAfter.toLong,
        Seq(stageLabel -> "remaining")
      ) >>
      Metrics[F]
        .incrementCounterBy(
          "dag_global_snapshot_dag_tx_awaiting_parent_total",
          reactivated.toLong,
          Seq(outcomeLabel -> "reactivated")
        )
        .whenA(reactivated > 0) >>
      Metrics[F]
        .incrementCounterBy(
          "dag_global_snapshot_dag_tx_awaiting_parent_total",
          expired.toLong,
          Seq(outcomeLabel -> "expired")
        )
        .whenA(expired > 0) >>
      Metrics[F]
        .incrementCounterBy(
          "dag_global_snapshot_dag_tx_awaiting_parent_total",
          gapTooLarge.toLong,
          Seq(outcomeLabel -> "rejected_gap_too_large")
        )
        .whenA(gapTooLarge > 0) >>
      Metrics[F]
        .incrementCounterBy(
          "dag_global_snapshot_dag_tx_awaiting_parent_total",
          backlogFull.toLong,
          Seq(outcomeLabel -> "rejected_backlog_full")
        )
        .whenA(backlogFull > 0)
}
