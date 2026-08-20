package io.constellationnetwork.currency.schema

import cats.syntax.order._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.semver.SnapshotVersion

import eu.timepit.refined.auto._

/** Versioned Currency snapshot semantics.
  *
  * This is deliberately independent of the Tessellation/metagraph jar SemVer. The
  * value is carried in every signed Currency snapshot, so historical replay selects
  * semantics from the lineage itself after validating the transition from its signed
  * parent.
  */
object CurrencySnapshotSemantics {
  val LegacyVersion: SnapshotVersion = SnapshotVersion("0.0.1")
  val DeterministicHistoryVersion: SnapshotVersion = SnapshotVersion("1.0.0")

  def usesDeterministicHistory(version: SnapshotVersion): Boolean =
    version == DeterministicHistoryVersion

  /** An absent public activation resolves to [[SnapshotOrdinal.MaxValue]] and must
    * stay dormant even for a malformed or theoretical MaxValue reference.
    */
  def isActivationAuthorized(
    activationReference: SnapshotOrdinal,
    activationOrdinal: SnapshotOrdinal
  ): Boolean =
    activationOrdinal != SnapshotOrdinal.MaxValue && activationReference >= activationOrdinal

  /** Legacy processing history is proven through the selected Global L0 view when
    * no still-unacknowledged ordinal at or below that view exists. Ordinals above
    * the selected view have not become inputs to this Currency artifact yet and do
    * not make a historical transition ambiguous.
    */
  def legacyHistoryResolvedThrough(
    unappliedGlobalChangeOrdinals: SortedSet[SnapshotOrdinal],
    selectedGlobalSyncOrdinal: SnapshotOrdinal
  ): Boolean =
    unappliedGlobalChangeOrdinals.forall(_ > selectedGlobalSyncOrdinal)

  /** Selects the next signed snapshot-protocol version.
    *
    * `activationReference` is the consensus-derived Global L0 ordinal selected for
    * the Currency artifact, never a process clock or the validator's current tip.
    * Once activated, a lineage cannot downgrade. The initial transition waits until
    * GL0 reports no unresolved spend-action history because legacy process-local
    * state cannot prove which non-empty ordinals were already applied.
    */
  def nextVersion(
    parentVersion: SnapshotVersion,
    activationReference: SnapshotOrdinal,
    activationOrdinal: SnapshotOrdinal,
    transitionHistoryProven: Boolean
  ): SnapshotVersion =
    if (usesDeterministicHistory(parentVersion)) DeterministicHistoryVersion
    else if (isActivationAuthorized(activationReference, activationOrdinal) && transitionHistoryProven) DeterministicHistoryVersion
    else LegacyVersion
}
