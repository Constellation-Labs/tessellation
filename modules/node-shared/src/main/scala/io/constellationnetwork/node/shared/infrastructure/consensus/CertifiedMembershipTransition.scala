package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.schema.peer.PeerId

/** Structural policy for v35 Global L0 signing-lease changes.
  *
  * A certified proposal may expand the committee using the existing admission lane, but it may never contract it. An eviction is valid only
  * as one half of an equal-sized atomic replacement. The complete admitted/evicted sets are already covered by the v35 ProposalValue QC, so
  * this helper deliberately introduces no new message field or hash domain.
  */
object CertifiedMembershipTransition {

  sealed trait Kind extends Product with Serializable
  object Kind {
    case object Hold extends Kind
    case object Expansion extends Kind
    case object Replacement extends Kind
  }

  final case class Validated(kind: Kind, nextCommittee: Set[PeerId])

  /** Proposal-construction result over caller-owned certificate types.
    *
    * Selection is generic because certificate validation and deterministic ordering belong to each layer, while the cardinality/headroom
    * invariant is shared. Callers pass already validated, deterministically ordered candidates and target projections.
    */
  final case class ProposalSelection[A, E](admissions: List[A], evictions: List[E])

  /** A denominator-neutral replacement must install a peer from the open ReadyAtTip lane.
    *
    * Probation is intentionally a separate recovery lane: its target may still be Observing or WaitingForReady and its wider witness pool
    * certifies catch-up, not immediate signing participation. Pairing that certificate with an eviction could replace a silent signer with
    * another non-signing seat. Validators therefore reject the combination even though the generic cardinality transition is well formed.
    */
  def validateReplacementAdmissionLane(
    admittedPeers: Set[PeerId],
    evictedPeers: Set[PeerId],
    probationPeers: Set[PeerId]
  ): Either[String, Unit] =
    Either.cond(
      evictedPeers.isEmpty || admittedPeers.intersect(probationPeers).isEmpty,
      (),
      "certified_membership_replacement_requires_open_ready_admission"
    )

  /** Validate the list-shaped certificate surface before reducing it to the set-shaped [[CertifiedConsensus.ProposalValue]]. Proposal
    * validation already checks each ACS/ECS independently; keeping the duplicate-target rule here as well makes the atomic transition fail
    * closed if that validation pipeline is ever reordered.
    */
  def validateCertificateTargets(
    roundStartCommittee: Set[PeerId],
    admittedPeers: List[PeerId],
    evictedPeers: List[PeerId],
    maxChanges: Int
  ): Either[String, Validated] =
    for {
      _ <- Either.cond(
        admittedPeers.distinct.size == admittedPeers.size,
        (),
        "certified_membership_duplicate_admission_target"
      )
      _ <- Either.cond(
        evictedPeers.distinct.size == evictedPeers.size,
        (),
        "certified_membership_duplicate_eviction_target"
      )
      validated <- validate(roundStartCommittee, admittedPeers.toSet, evictedPeers.toSet, maxChanges)
    } yield validated

  def validate(
    roundStartCommittee: Set[PeerId],
    admittedPeers: Set[PeerId],
    evictedPeers: Set[PeerId],
    maxChanges: Int
  ): Either[String, Validated] = {
    val limit = math.max(0, maxChanges)
    val overlap = admittedPeers.intersect(evictedPeers)
    val admittedAlreadySeated = admittedPeers.intersect(roundStartCommittee)
    val evictedNotSeated = evictedPeers -- roundStartCommittee

    for {
      _ <- Either.cond(overlap.isEmpty, (), "certified_membership_admit_evict_overlap")
      _ <- Either.cond(admittedAlreadySeated.isEmpty, (), "certified_membership_admitted_already_seated")
      _ <- Either.cond(evictedNotSeated.isEmpty, (), "certified_membership_evicted_not_seated")
      _ <- Either.cond(admittedPeers.size <= limit, (), "certified_membership_admissions_over_cap")
      _ <- Either.cond(evictedPeers.size <= limit, (), "certified_membership_evictions_over_cap")
      kind <-
        if (evictedPeers.isEmpty)
          Right(if (admittedPeers.isEmpty) Kind.Hold else Kind.Expansion)
        else
          Either.cond(
            admittedPeers.nonEmpty && admittedPeers.size == evictedPeers.size,
            Kind.Replacement: Kind,
            "certified_membership_eviction_requires_equal_admission"
          )
      next = (roundStartCommittee -- evictedPeers) ++ admittedPeers
      _ <- Either.cond(
        kind != Kind.Replacement || next.size == roundStartCommittee.size,
        (),
        "certified_membership_replacement_changed_size"
      )
    } yield Validated(kind, next)
  }

  /** Validate and apply while preserving the inherited committee order and appending new admissions in stable PeerId order, matching
    * existing admission semantics.
    */
  def applyTo(
    roundStartCommittee: List[PeerId],
    admittedPeers: Set[PeerId],
    evictedPeers: Set[PeerId],
    maxChanges: Int
  ): Either[String, List[PeerId]] =
    validate(roundStartCommittee.toSet, admittedPeers, evictedPeers, maxChanges).map { _ =>
      val retained = roundStartCommittee.distinct.filterNot(evictedPeers.contains)
      val retainedSet = retained.toSet
      retained ++ admittedPeers.toList.sorted.filterNot(retainedSet.contains)
    }

  /** Local Core prepare-vote policy for a structurally valid certified membership value.
    *
    * Proof subsets are intentionally local and therefore can only make this voter abstain from an admission-only expansion. They never
    * alter validation or the derived next committee. An equal-sized replacement keeps Q(N) unchanged and is independent of the local proof
    * subset.
    */
  def allowsPrepareVote(
    roundStartCommittee: Set[PeerId],
    locallyObservedParentSigners: Set[PeerId],
    admittedPeers: Set[PeerId],
    evictedPeers: Set[PeerId],
    quorumThresholdFraction: Double,
    maxChanges: Int
  ): Boolean =
    validate(roundStartCommittee, admittedPeers, evictedPeers, maxChanges).exists {
      case Validated(Kind.Replacement, _) => true
      case Validated(Kind.Hold, _)        => true
      case Validated(Kind.Expansion, _) =>
        FinalityHeadroom
          .evaluate(
            roundStartCommittee,
            locallyObservedParentSigners,
            quorumThresholdFraction,
            math.max(1, admittedPeers.size)
          )
          .allowsExpansion
    }

  /** Select a certified membership transition that the constructing node can also prepare.
    *
    * An available eviction is paired one-for-one with an admission and therefore needs no N+1 headroom. Without a pair, admissions are
    * emitted only when the same local parent-proof invariant used by Core prepare voting permits expansion. This prevents asymmetric
    * delivery of an atomic-intent ACS from making an honest leader repeatedly propose a value it would itself refuse to prepare.
    */
  def selectForProposal[A, E](
    roundStartCommittee: Set[PeerId],
    locallyObservedParentSigners: Set[PeerId],
    admissions: List[A],
    evictions: List[E],
    admissionTarget: A => PeerId,
    evictionTarget: E => PeerId,
    quorumThresholdFraction: Double,
    maxChanges: Int
  ): ProposalSelection[A, E] = {
    val limit = math.max(0, maxChanges)
    val boundedAdmissions = admissions.take(limit)
    val boundedEvictions = evictions.take(math.min(limit, boundedAdmissions.size))

    if (boundedEvictions.nonEmpty) {
      val pairedAdmissions = boundedAdmissions.take(boundedEvictions.size)
      val admittedPeers = pairedAdmissions.map(admissionTarget).toSet
      val evictedPeers = boundedEvictions.map(evictionTarget).toSet

      if (
        allowsPrepareVote(
          roundStartCommittee,
          locallyObservedParentSigners,
          admittedPeers,
          evictedPeers,
          quorumThresholdFraction,
          limit
        )
      ) ProposalSelection(pairedAdmissions, boundedEvictions)
      else ProposalSelection(List.empty, List.empty)
    } else {
      val admittedPeers = boundedAdmissions.map(admissionTarget).toSet
      if (
        allowsPrepareVote(
          roundStartCommittee,
          locallyObservedParentSigners,
          admittedPeers,
          Set.empty,
          quorumThresholdFraction,
          limit
        )
      ) ProposalSelection(boundedAdmissions, List.empty)
      else ProposalSelection(List.empty, List.empty)
    }
  }
}
