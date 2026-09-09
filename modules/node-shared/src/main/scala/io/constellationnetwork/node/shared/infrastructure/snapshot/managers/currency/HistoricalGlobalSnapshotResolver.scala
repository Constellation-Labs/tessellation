package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

import cats.syntax.order._
import cats.syntax.traverse._

import scala.util.control.NoStackTrace

import io.constellationnetwork.schema.SnapshotOrdinal

/** Resolves inputs used during currency-snapshot recreation exclusively from the consensus-retained Global L0 window.
  *
  * Artifact derivation must not fall back to a node's local archive (or to a peer fetch): archive availability is not a signed consensus
  * input and may legitimately differ between otherwise-honest validators. The resolver is generic so every historical dependency follows
  * the same interval and lookup rules.
  */
object HistoricalGlobalSnapshotResolver {

  sealed trait Purpose extends Product with Serializable {
    def metricLabel: String
  }
  case object SyncTarget extends Purpose {
    val metricLabel: String = "sync_target"
  }
  case object UnappliedSpendAction extends Purpose {
    val metricLabel: String = "unapplied_spend_action"
  }

  sealed abstract class Error(
    val purpose: Purpose,
    val required: SnapshotOrdinal,
    val parent: SnapshotOrdinal
  ) extends RuntimeException
      with NoStackTrace

  final case class OutsideRetainedWindow(
    override val purpose: Purpose,
    override val required: SnapshotOrdinal,
    oldest: SnapshotOrdinal,
    override val parent: SnapshotOrdinal
  ) extends Error(purpose, required, parent) {
    override val getMessage: String =
      s"Historical Global L0 dependency is outside the retained window: purpose=${purpose.metricLabel} required=$required oldest=$oldest parent=$parent"
  }

  final case class MissingInsideRetainedWindow(
    override val purpose: Purpose,
    override val required: SnapshotOrdinal,
    override val parent: SnapshotOrdinal
  ) extends Error(purpose, required, parent) {
    override val getMessage: String =
      s"Historical Global L0 dependency is missing inside the retained window: purpose=${purpose.metricLabel} required=$required parent=$parent"
  }

  def oldestSupported(parent: SnapshotOrdinal, retainedCount: Int): SnapshotOrdinal = {
    require(retainedCount > 0, "retainedCount must be positive")
    SnapshotOrdinal.unsafeApply(Math.max(SnapshotOrdinal.MinValue.value.value, parent.value.value - (retainedCount.toLong - 1L)))
  }

  def contains(parent: SnapshotOrdinal, required: SnapshotOrdinal, retainedCount: Int): Boolean = {
    val oldest = oldestSupported(parent, retainedCount)
    required >= oldest && required <= parent
  }

  def resolve[A](
    purpose: Purpose,
    required: SnapshotOrdinal,
    parent: SnapshotOrdinal,
    retainedCount: Int,
    retained: Iterable[A]
  )(ordinalOf: A => SnapshotOrdinal): Either[Error, A] = {
    val oldest = oldestSupported(parent, retainedCount)

    if (required < oldest || required > parent)
      Left(OutsideRetainedWindow(purpose, required, oldest, parent))
    else
      retained.find(ordinalOf(_) == required).toRight(MissingInsideRetainedWindow(purpose, required, parent))
  }

  /** Resolves the complete set before the caller applies any effect. Sorting makes error selection stable for malformed inputs containing
    * more than one missing dependency.
    */
  def resolveAll[A](
    purpose: Purpose,
    required: Set[SnapshotOrdinal],
    parent: SnapshotOrdinal,
    retainedCount: Int,
    retained: Iterable[A]
  )(ordinalOf: A => SnapshotOrdinal): Either[Error, List[A]] =
    required.toList.sorted.traverse(resolve(purpose, _, parent, retainedCount, retained)(ordinalOf))
}
