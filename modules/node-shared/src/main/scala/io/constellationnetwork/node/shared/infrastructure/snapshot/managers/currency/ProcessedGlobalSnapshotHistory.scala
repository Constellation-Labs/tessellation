package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

import cats.syntax.order._

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSyncView
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.artifact.{GlobalSnapshotsProcessed, SharedArtifact}

/** Derives cumulative, still-unacknowledged Global L0 ordinals from signed Currency L0 history and consensus-carried GL0 state.
  *
  * No process-local cache participates. Consequently a warm creator and a just-restarted validator derive the same set.
  */
object ProcessedGlobalSnapshotHistory {
  def payload(artifacts: Iterable[SharedArtifact]): SortedSet[SnapshotOrdinal] =
    artifacts.collect { case value: GlobalSnapshotsProcessed => value.ordinals }.flatten
      .to(SortedSet)

  final case class ProcessedHistoryUnproven(ordinals: SortedSet[SnapshotOrdinal])
      extends RuntimeException(
        s"Previously-visible unapplied Global L0 ordinals are not proven by the signed parent artifact: ${ordinals.mkString(",")}"
      )
      with NoStackTrace

  final case class Plan(carried: SortedSet[SnapshotOrdinal], newlyRequired: SortedSet[SnapshotOrdinal]) {
    val cumulative: SortedSet[SnapshotOrdinal] = carried ++ newlyRequired
  }

  def derive(
    previousGlobalSyncView: Option[GlobalSyncView],
    previouslyDeclared: SortedSet[SnapshotOrdinal],
    unapplied: SortedSet[SnapshotOrdinal],
    currentGlobalSyncOrdinal: SnapshotOrdinal
  ): Either[ProcessedHistoryUnproven, Plan] = {
    val carried = previouslyDeclared.intersect(unapplied)
    val visiblePreviously = previousGlobalSyncView.fold(SortedSet.empty[SnapshotOrdinal]) { view =>
      unapplied.filter(_ <= view.ordinal)
    }
    val unproven = visiblePreviously -- carried

    if (unproven.nonEmpty) Left(ProcessedHistoryUnproven(unproven))
    else {
      val newlyRequired = unapplied.filter(_ <= currentGlobalSyncOrdinal) -- carried
      Right(Plan(carried, newlyRequired))
    }
  }
}
