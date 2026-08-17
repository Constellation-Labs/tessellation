package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.state.QuorumPolicy
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

/** Bounded node-local history of the actual proof sets observed on finalized parent snapshots.
  *
  * Finalized proof subsets are deliberately not consensus state: honest nodes may persist different valid threshold-crossing subsets. This
  * history may therefore control local AdmissionVote emission only. An AdmissionCertificate remains the sole membership authority.
  */
object AdmissionProofHistory {

  val RequiredConsecutiveParents: Int = TierTransitions.DemotionConsecutiveMisses

  final case class ParentProofs(ordinal: Long, hash: Hash, signers: Set[PeerId])

  final case class History(parents: Vector[ParentProofs]) {
    def depth: Int = parents.size
  }

  object History {
    val empty: History = History(Vector.empty)
  }

  final case class Evaluation(
    observedParents: Int,
    requiredParents: Int,
    qualifyingParents: Int,
    currentFinalityFloor: Int,
    nextFinalityFloor: Int
  ) {
    val raisesFinalityFloor: Boolean = nextFinalityFloor > currentFinalityFloor
    val historyComplete: Boolean = observedParents >= requiredParents
    val allowsAdmission: Boolean = !raisesFinalityFloor || (historyComplete && qualifyingParents >= requiredParents)
  }

  /** Add one finalized parent observation.
    *
    * Re-observing the same `(ordinal, hash)` is idempotent because the monitor evaluates admission repeatedly within one round. A gap,
    * rollback, or same-ordinal hash replacement starts a fresh lineage. Explicit download/rollback initialization also clears the owning
    * Ref before this function observes the installed outcome.
    */
  def observe(
    previous: History,
    ordinal: Long,
    hash: Hash,
    signers: Set[PeerId],
    requiredParents: Int = RequiredConsecutiveParents
  ): History = {
    val required = math.max(1, requiredParents)
    val current = ParentProofs(ordinal, hash, signers)

    previous.parents.lastOption match {
      case Some(last) if last.ordinal == ordinal && last.hash === hash => previous
      case Some(last) if last.ordinal < Long.MaxValue && last.ordinal + 1L == ordinal =>
        History((previous.parents :+ current).takeRight(required))
      case _ => History(Vector(current))
    }
  }

  /** Repeat the exact next-floor invariant across the bounded parent history.
    *
    * Signer identities may rotate: finality requires the floor count on every round, not an identical signer cohort. Requiring every stored
    * parent to meet `Q(N + A)` rejects the one-round participation spike that caused IntegrationNet's grow-then-wedge oscillator without
    * centralizing admission around whichever signatures happened to arrive first on this node.
    */
  def evaluate(
    history: History,
    currentCommittee: Set[PeerId],
    quorumThresholdFraction: Double,
    additionalSeats: Int,
    requiredParents: Int = RequiredConsecutiveParents
  ): Evaluation = {
    val required = math.max(1, requiredParents)
    val additional = math.max(1, additionalSeats)
    val currentFloor = math.max(1, QuorumPolicy.fromFraction(currentCommittee.size, quorumThresholdFraction))
    val nextFloor = math.max(1, QuorumPolicy.fromFraction(currentCommittee.size + additional, quorumThresholdFraction))
    val recent = history.parents.takeRight(required)
    val qualifying = recent.count(parent => parent.signers.intersect(currentCommittee).size >= nextFloor)

    Evaluation(
      observedParents = recent.size,
      requiredParents = required,
      qualifyingParents = qualifying,
      currentFinalityFloor = currentFloor,
      nextFinalityFloor = nextFloor
    )
  }
}
