package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.syntax.order._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.CurrencySnapshotSemantics
import io.constellationnetwork.currency.schema.globalSnapshotSync.{
  GlobalSnapshotSync,
  GlobalSnapshotSyncOrdinal,
  GlobalSnapshotSyncReference
}
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency.HistoricalGlobalSnapshotResolver
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.cluster.SessionToken
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

/** Pure classification and deterministic checks for the schema-compatible Currency L0 recovery sync transition.
  *
  * The operator flag is intentionally absent. It authorizes emission on the rollback lead, never validation. Validators recognize the reset
  * solely from signed artifact content and consensus-carried parent state.
  */
object RecoveryGlobalSnapshotSync {

  /** Public-ordinal authorization for the reusable signed reset. An absent setting resolves to MaxValue and stays dormant. MinValue cannot
    * authorize a reset because it does not name a concrete retained GL0 dependency.
    */
  def isActivationAuthorized(reference: SnapshotOrdinal, activationOrdinal: SnapshotOrdinal): Boolean =
    CurrencySnapshotSemantics.isActivationAuthorized(reference, activationOrdinal)

  sealed trait RefreshMode extends Product with Serializable {
    def metricLabel: String
  }
  case object ChainStart extends RefreshMode {
    val metricLabel: String = "chain_start"
  }
  final case class Chained(parent: GlobalSnapshotSyncReference) extends RefreshMode {
    val metricLabel: String = "chained"
  }
  case object ResetInheritedMultiPeerView extends RefreshMode {
    val metricLabel: String = "reset"
  }

  def classify(
    self: PeerId,
    inherited: SortedMap[PeerId, GlobalSnapshotSyncReference]
  ): RefreshMode =
    if (inherited.isEmpty) ChainStart
    else if (inherited.keySet == SortedSet(self)) Chained(inherited(self))
    else ResetInheritedMultiPeerView

  /** A reset is distinguishable from an ordinary first declaration by the authoritative singleton signer set. A newly admitted signer in a
    * multi-member committee may legitimately publish a MinValue-parent declaration and must stay on the ordinary validation path.
    */
  def hasResetShape(
    signer: PeerId,
    parentOrdinal: GlobalSnapshotSyncOrdinal,
    inheritedPeerIds: Set[PeerId],
    currentSigners: Set[PeerId]
  ): Boolean =
    currentSigners == Set(signer) &&
      parentOrdinal == GlobalSnapshotSyncOrdinal.MinValue &&
      inheritedPeerIds.exists(_ != signer)

  sealed trait ValidationError extends Product with Serializable
  case object CurrentSignerSetNotSingleton extends ValidationError
  case object ResetSignerDoesNotMatchCurrentSigner extends ValidationError
  case object InheritedViewIsNotMultiPeerForSigner extends ValidationError
  case object ResetParentIsNotMinValue extends ValidationError
  case object ResetSessionIsNotNewer extends ValidationError
  case object ResetAnchorIsNotCanonicalRecentSnapshot extends ValidationError
  case object ResetAnchorAfterCurrentGlobalParent extends ValidationError
  case object ResetSelectedTargetOutsideRetainedWindow extends ValidationError
  case object ResetBeforeSnapshotProtocolV1Activation extends ValidationError
  case object MetagraphHasUnappliedGlobalChanges extends ValidationError
  case object MetagraphLineageIsNotDormant extends ValidationError

  final case class ValidationContext(
    currentSigners: Set[PeerId],
    inheritedPeerIds: Set[PeerId],
    inheritedSessions: SortedMap[PeerId, SessionToken],
    currentGlobalParent: SnapshotOrdinal,
    recentGlobalSnapshots: SortedMap[SnapshotOrdinal, Hash],
    retainedCount: Int,
    syncOffset: Long,
    metagraphLastAcceptedOn: SnapshotOrdinal,
    unappliedGlobalChangeOrdinals: SortedSet[SnapshotOrdinal],
    snapshotProtocolV1ActivationOrdinal: SnapshotOrdinal
  )

  def validateReset(
    signer: PeerId,
    reset: GlobalSnapshotSync,
    context: ValidationContext
  ): Either[ValidationError, Unit] = {
    val oldest = HistoricalGlobalSnapshotResolver.oldestSupported(context.currentGlobalParent, context.retainedCount)
    val selectedTarget = SnapshotOrdinal(reset.globalSnapshotOrdinal.value.value - context.syncOffset)

    Either
      .cond(context.currentSigners == Set(signer), (), CurrentSignerSetNotSingleton)
      .flatMap(_ => Either.cond(context.currentSigners.contains(signer), (), ResetSignerDoesNotMatchCurrentSigner))
      .flatMap(_ => Either.cond(context.inheritedPeerIds.exists(_ != signer), (), InheritedViewIsNotMultiPeerForSigner))
      .flatMap(_ => Either.cond(reset.parentOrdinal.value.value == 0L, (), ResetParentIsNotMinValue))
      .flatMap(_ =>
        Either.cond(
          context.inheritedSessions.get(signer).forall(reset.session > _),
          (),
          ResetSessionIsNotNewer
        )
      )
      .flatMap(_ =>
        Either.cond(
          context.recentGlobalSnapshots.get(reset.globalSnapshotOrdinal).contains(reset.globalSnapshotHash),
          (),
          ResetAnchorIsNotCanonicalRecentSnapshot
        )
      )
      .flatMap(_ => Either.cond(reset.globalSnapshotOrdinal <= context.currentGlobalParent, (), ResetAnchorAfterCurrentGlobalParent))
      .flatMap(_ =>
        Either.cond(
          selectedTarget.exists(isActivationAuthorized(_, context.snapshotProtocolV1ActivationOrdinal)),
          (),
          ResetBeforeSnapshotProtocolV1Activation
        )
      )
      .flatMap(_ =>
        Either.cond(
          selectedTarget.exists(target =>
            target >= oldest && target <= context.currentGlobalParent && context.recentGlobalSnapshots.contains(target)
          ),
          (),
          ResetSelectedTargetOutsideRetainedWindow
        )
      )
      .flatMap(_ => Either.cond(context.unappliedGlobalChangeOrdinals.isEmpty, (), MetagraphHasUnappliedGlobalChanges))
      .flatMap(_ => Either.cond(context.metagraphLastAcceptedOn < oldest, (), MetagraphLineageIsNotDormant))
  }
}
