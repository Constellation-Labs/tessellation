package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Show
import cats.data.NonEmptySet
import cats.syntax.show._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.ext.codecs.NonEmptySetCodec
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe._
import io.circe.syntax._

object declaration {

  implicit val signatureProofsEncoder: Encoder[NonEmptySet[SignatureProof]] =
    NonEmptySetCodec.encoder[SignatureProof]
  implicit val signatureProofsDecoder: Decoder[NonEmptySet[SignatureProof]] =
    NonEmptySetCodec.decoder[SignatureProof]

  // v15: explicit Show for Proposal.observedSelfHealth resolves an ambiguity between
  // `cats.Show.catsShowForSortedMap` and the package-object `showSortedMapAsList` that both
  // match the field's `SortedMap[PeerId, SelfHealthHint]` type during derevo's `@derive(show)`
  // chain. Local scope wins, so this fixes the derivation without changing wire format.
  implicit val showSortedSelfHealth: Show[SortedMap[PeerId, SelfHealthHint]] =
    Show.show(_.toList.map { case (k, v) => s"${k.show}->${v.show}" }.mkString("{", ",", "}"))

  @derive(eqv, show, encoder, decoder)
  sealed trait PeerDeclaration {
    def facilitatorsHash: Hash
    def lastSnapshotHash: Hash
  }

  @derive(eqv, show, encoder, decoder)
  case class Facility(
    eventHashes: Set[Hash],
    candidates: Candidates,
    trigger: Option[ConsensusTrigger],
    facilitatorsHash: Hash,
    lastGlobalSnapshotOrdinal: SnapshotOrdinal,
    lastSnapshotHash: Hash,
    consensusConfigHash: Option[Hash] = None,
    // Self-health throttle, see docs/consensus/self-health-throttle.md.
    // The peer's own current `SelfHealthHint` derived from `LocalHealthMonitor`. The leader
    // aggregates these into `Proposal.observedSelfHealth` for consensus-agreed propagation;
    // `selectLeaderWeighted` in the next round demotes Degraded peers to tier 1 and Critical
    // peers to tier 2. Optional with default None so the field is wire-compatible with older
    // versions, although distinct advertised versions (or `CL_VERSION_HASH` values) are rejected
    // by the join-time `versionHash` gate.
    selfHealthHint: Option[SelfHealthHint] = None,
    // v19 phase 2: per-facilitator wall-clock at signing time (raw millis, no bucketing).
    // Acquired via `Clock[F].realTime.map(_.toMillis)` in the Facility build effect. The
    // leader-elected outcome's `consensusEndTime` is the median of these values across the
    // accepted Facility set, clamped against `parent.consensusEndTime + 1` for Bitcoin
    // MTP-style anti-regression. See docs/consensus/view-from-time-anchor.md.
    //
    // Optional with default None: pre-v19 Facilities decode as `None`; the median
    // computation skips `None` values and treats < n/2+1 carrying clocks as
    // bootstrap (falls back to phase 1 viewChangeVotes-driven view derivation). Jar hash
    // already gates v18 <-> v19 peer connection so the partial-deploy window is
    // controlled at handshake; the field is Option-wrapped purely for derevo
    // back-compat with snapshots / facilities written before this field existed.
    proposerClockMs: Option[Long] = None
  ) extends PeerDeclaration

  @derive(eqv, show, encoder, decoder)
  case class ProposalQC(
    view: Long,
    proposalHash: Hash,
    facilitatorsHash: Hash,
    signatures: NonEmptySet[SignatureProof]
  )

  // ViewChangeVote / ViewChangeCertificate must be declared BEFORE Proposal: Proposal embeds
  // `Option[ViewChangeCertificate]` and derevo's `@derive(encoder)` macro materializes its
  // implicit-lookup chain at macro-expansion time. With the previous ordering (Proposal before
  // VCC), the `Encoder[Signed[ViewChangeVote]]` reference inside the VCC-encoding path was
  // captured as a forward-reference that circe-generic's lazy-init resolver set to null — every
  // attempt to serialize a view>0 Proposal (which carries a VCC) produced
  // `NullPointerException: ... circeGenericEncoderForvalue is null` at Signed.scala:56. The
  // round that triggered the view change never delivered its proposal and thrashed forever.
  @derive(eqv, show, encoder, decoder)
  case class ViewChangeVote(
    fromView: Long,
    toView: Long,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash,
    highestKnownQc: Option[ProposalQC]
  ) extends PeerDeclaration

  object ViewChangeVote {
    implicit val ordering: Ordering[ViewChangeVote] =
      Ordering.by { v =>
        val qcPart = v.highestKnownQc.fold("-") { qc =>
          s"${qc.view}|${qc.proposalHash.value}|${qc.facilitatorsHash.value}"
        }
        (v.fromView, v.toView, v.facilitatorsHash.value, v.lastSnapshotHash.value, qcPart)
      }
    implicit val order: cats.kernel.Order[ViewChangeVote] = cats.kernel.Order.fromOrdering(ordering)
  }

  // Explicit codecs for `Signed[ViewChangeVote]` and `ViewChangeCertificate`. Without these, the
  // magnolia/circe-generic derivation chain inside `Proposal`'s derived encoder captures a
  // forward-reference that resolves to null at runtime (observed as `NullPointerException: Cannot
  // invoke Encoder.apply(Object) because circeGenericEncoderForvalue is null` at Signed.scala:56).
  // Every view>0 proposal that embeds a VCC crashed in `spreadProposal`, leading to endless round
  // thrashing post-view-change. See `ViewChangeCertificateSuite` regression tests for the shape
  // that previously failed.
  //
  // These explicit codecs also short-circuit magnolia's lazy-init chain: implicit resolution
  // prefers same-level explicit implicits over companion-object derived ones, so `Proposal`'s
  // derived encoder sees these codecs when it needs to encode a VCC / Signed[ViewChangeVote].
  // `Encoder.instance` + `asJson`/`as[A]` inside the closures forces implicit-encoder/decoder
  // resolution at call time, not at codec-construction time. The alternative `Encoder.forProduct2`
  // captures the inner encoder as an eager constructor field, which can be null when derevo's
  // magnolia-derived `Encoder[ViewChangeVote]` participates in a lazy-init chain that hasn't
  // fully resolved yet. The symptom was `NullPointerException: circeGenericEncoderForvalue is
  // null` at Signed.scala:56 every time a view>0 Proposal carrying a VCC was serialized.
  implicit val signedViewChangeVoteEncoder: Encoder[Signed[ViewChangeVote]] =
    Encoder.instance { sv =>
      Json.obj("value" -> sv.value.asJson, "proofs" -> sv.proofs.asJson)
    }
  implicit val signedViewChangeVoteDecoder: Decoder[Signed[ViewChangeVote]] =
    (c: HCursor) =>
      for {
        value <- c.downField("value").as[ViewChangeVote]
        proofs <- c.downField("proofs").as[NonEmptySet[SignatureProof]]
      } yield Signed(value, proofs)

  implicit val viewChangeVotesEncoder: Encoder[NonEmptySet[Signed[ViewChangeVote]]] =
    NonEmptySetCodec.encoder[Signed[ViewChangeVote]]
  implicit val viewChangeVotesDecoder: Decoder[NonEmptySet[Signed[ViewChangeVote]]] =
    NonEmptySetCodec.decoder[Signed[ViewChangeVote]]

  @derive(eqv, show, encoder, decoder)
  case class ViewChangeCertificate(
    fromView: Long,
    toView: Long,
    facilitatorsHash: Hash,
    votes: NonEmptySet[Signed[ViewChangeVote]]
  ) {
    def highestQcInVcc: Option[ProposalQC] = {
      val qcs = votes.toSortedSet.toList.flatMap(_.value.highestKnownQc)
      if (qcs.isEmpty) None
      else {
        val maxView = qcs.map(_.view).max
        val atMaxView = qcs.filter(_.view == maxView)
        val distinctHashes = atMaxView.map(_.proposalHash).toSet
        if (distinctHashes.size == 1) atMaxView.headOption
        else None
      }
    }
  }

  @derive(eqv, show)
  sealed trait TimeoutReason

  object TimeoutReason {
    case object NoProgress extends TimeoutReason
    case object QuorumInfeasible extends TimeoutReason

    implicit val ordering: Ordering[TimeoutReason] = Ordering.by(_.toString)

    implicit val timeoutReasonEncoder: Encoder[TimeoutReason] =
      Encoder.encodeString.contramap {
        case TimeoutReason.NoProgress       => "NoProgress"
        case TimeoutReason.QuorumInfeasible => "QuorumInfeasible"
      }
    implicit val timeoutReasonDecoder: Decoder[TimeoutReason] =
      Decoder.decodeString.emap {
        case "NoProgress"       => Right(TimeoutReason.NoProgress)
        case "QuorumInfeasible" => Right(TimeoutReason.QuorumInfeasible)
        case other              => Left(s"Invalid TimeoutReason: $other")
      }
  }

  @derive(eqv, show)
  case class TimeoutVote(
    fromView: Long,
    toView: Long,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash,
    highestKnownQc: Option[ProposalQC],
    reason: TimeoutReason
  ) extends PeerDeclaration

  object TimeoutVote {
    implicit val ordering: Ordering[TimeoutVote] =
      Ordering.by { v =>
        val qcPart = v.highestKnownQc.fold("-") { qc =>
          s"${qc.view}|${qc.proposalHash.value}|${qc.facilitatorsHash.value}"
        }
        (v.fromView, v.toView, v.facilitatorsHash.value, v.lastSnapshotHash.value, qcPart, v.reason.toString)
      }
    implicit val order: cats.kernel.Order[TimeoutVote] = cats.kernel.Order.fromOrdering(ordering)

    implicit val timeoutVoteEncoder: Encoder[TimeoutVote] =
      Encoder.instance { v =>
        Json.obj(
          "fromView" -> v.fromView.asJson,
          "toView" -> v.toView.asJson,
          "facilitatorsHash" -> v.facilitatorsHash.asJson,
          "lastSnapshotHash" -> v.lastSnapshotHash.asJson,
          "highestKnownQc" -> v.highestKnownQc.asJson,
          "reason" -> TimeoutReason.timeoutReasonEncoder(v.reason)
        )
      }
    implicit val timeoutVoteDecoder: Decoder[TimeoutVote] =
      (c: HCursor) =>
        for {
          fromView <- c.downField("fromView").as[Long]
          toView <- c.downField("toView").as[Long]
          facilitatorsHash <- c.downField("facilitatorsHash").as[Hash]
          lastSnapshotHash <- c.downField("lastSnapshotHash").as[Hash]
          highestKnownQc <- c.downField("highestKnownQc").as[Option[ProposalQC]]
          reason <- c.downField("reason").as(TimeoutReason.timeoutReasonDecoder)
        } yield TimeoutVote(fromView, toView, facilitatorsHash, lastSnapshotHash, highestKnownQc, reason)
  }

  implicit val signedTimeoutVoteEncoder: Encoder[Signed[TimeoutVote]] =
    Encoder.instance { sv =>
      Json.obj("value" -> TimeoutVote.timeoutVoteEncoder(sv.value), "proofs" -> sv.proofs.asJson)
    }
  implicit val signedTimeoutVoteDecoder: Decoder[Signed[TimeoutVote]] =
    (c: HCursor) =>
      for {
        value <- c.downField("value").as(TimeoutVote.timeoutVoteDecoder)
        proofs <- c.downField("proofs").as[NonEmptySet[SignatureProof]]
      } yield Signed(value, proofs)

  implicit val timeoutVotesEncoder: Encoder[NonEmptySet[Signed[TimeoutVote]]] =
    NonEmptySetCodec.encoder[Signed[TimeoutVote]]
  implicit val timeoutVotesDecoder: Decoder[NonEmptySet[Signed[TimeoutVote]]] =
    NonEmptySetCodec.decoder[Signed[TimeoutVote]]

  @derive(eqv, show, encoder, decoder)
  case class TimeoutCertificate(
    fromView: Long,
    toView: Long,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash,
    reason: TimeoutReason,
    votes: NonEmptySet[Signed[TimeoutVote]]
  )

  // Eviction declarations: sparse negative-evidence mechanism that lets the committee
  // shrink for persistently-absent peers. Same architecture as VCC — signed votes
  // accumulate, a deterministic certificate assembles at quorum, the certificate is
  // embedded in the next Proposal, and on proposal acceptance the advancer adds the
  // target to `state.removedFacilitators`. The existing penalty + committee-shrink
  // pipeline fires downstream without further changes.
  //
  // EvictionVote, EvictionCertificate, and their codecs are declared BEFORE Proposal
  // so derevo's magnolia-derived Encoder[Proposal] chain resolves correctly when
  // Proposal embeds an eviction-cert field. See the ViewChangeVote/VCC comment above
  // for the specific forward-reference null pattern we are avoiding.
  @derive(eqv, show, encoder, decoder)
  sealed trait EvictionReason

  object EvictionReason {
    // Used by both existing Core-target stall repair and the bounded Tier-1 finality
    // audit. For a Tier-1 target, "Silent" means a Core quorum did not observe the
    // target's MajoritySignature before its local parent-round finalization cutoff;
    // it does not claim that the target never signed anywhere in the network.
    case object Silent extends EvictionReason
    // Extensibility reserved: LaggingTip, BadProposals, etc. Any new variant is a
    // consensus-critical schema change and requires cluster-wide deploy.

    implicit val ordering: Ordering[EvictionReason] = Ordering.by(_.toString)
  }

  @derive(eqv, show, encoder, decoder)
  case class EvictionVote(
    targetPeer: PeerId,
    reason: EvictionReason,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash
  ) extends PeerDeclaration

  object EvictionVote {
    implicit val ordering: Ordering[EvictionVote] =
      Ordering.by { v =>
        (v.targetPeer.value.value, v.reason.toString, v.facilitatorsHash.value, v.lastSnapshotHash.value)
      }
    implicit val order: cats.kernel.Order[EvictionVote] = cats.kernel.Order.fromOrdering(ordering)
  }

  // Explicit codecs for `Signed[EvictionVote]` — same rationale as the VCV codecs above.
  // Encoder.instance + asJson/as[A] forces implicit resolution at call time rather than
  // at codec-construction time, which avoids the circe-generic lazy-init null pattern
  // when Proposal's derived codec chains through an EvictionCertificate field.
  implicit val signedEvictionVoteEncoder: Encoder[Signed[EvictionVote]] =
    Encoder.instance { sv =>
      Json.obj("value" -> sv.value.asJson, "proofs" -> sv.proofs.asJson)
    }
  implicit val signedEvictionVoteDecoder: Decoder[Signed[EvictionVote]] =
    (c: HCursor) =>
      for {
        value <- c.downField("value").as[EvictionVote]
        proofs <- c.downField("proofs").as[NonEmptySet[SignatureProof]]
      } yield Signed(value, proofs)

  implicit val evictionVotesEncoder: Encoder[NonEmptySet[Signed[EvictionVote]]] =
    NonEmptySetCodec.encoder[Signed[EvictionVote]]
  implicit val evictionVotesDecoder: Decoder[NonEmptySet[Signed[EvictionVote]]] =
    NonEmptySetCodec.decoder[Signed[EvictionVote]]

  // `lastSnapshotHash` field binds the cert to a specific tip.
  // Without it, a leader could replay an older quorum of signed votes that matched the
  // current `facilitatorsHash` but referenced a stale tip -- followers would accept it.
  // Builders reject mixed-tip vote sets; advancers validate the cert's hash against the
  // current `lastOutcome.finished.snapshotHash` at proposal acceptance.
  @derive(eqv, show, encoder, decoder)
  case class EvictionCertificate(
    targetPeer: PeerId,
    reason: EvictionReason,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash,
    votes: NonEmptySet[Signed[EvictionVote]]
  )

  object EvictionCertificate {
    implicit val ordering: Ordering[EvictionCertificate] =
      Ordering.by { c =>
        (c.targetPeer.value.value, c.reason.toString, c.facilitatorsHash.value, c.lastSnapshotHash.value)
      }
    implicit val order: cats.kernel.Order[EvictionCertificate] = cats.kernel.Order.fromOrdering(ordering)
  }

  // Phase B2: re-admission of previously-removed peers into the committee.
  //
  // Mirrors B1 eviction-cert semantics. The
  // motivating failure was post-isolation re-admission: peers whose removalPenalty
  // expires rejoin `eligibleFacilitators` immediately, even if they have not caught
  // up to cluster tip. Committee then stalls because those peers cannot contribute
  // facility declarations, yet they are counted toward the quorum floor.
  //
  // Symmetric mechanism: healthy facilitators sign an `AdmissionVote` when they
  // observe the target participating at tip, votes accumulate in ConsensusResources,
  // `AdmissionCertificateBuilder` assembles at quorum, the cert is embedded in the
  // next Proposal, and on acceptance the advancer removes the target from
  // `readmissionCountdown` and returns them to `eligibleFacilitators`.
  //
  // Must be declared BEFORE Proposal for the same circe-derivation forward-reference
  // reason as EvictionCertificate above.
  @derive(eqv, show, encoder, decoder)
  sealed trait AdmissionReason

  object AdmissionReason {
    case object ReadyAtTip extends AdmissionReason
    // Extensibility reserved — new variants are consensus-critical schema changes.

    implicit val ordering: Ordering[AdmissionReason] = Ordering.by(_.toString)
  }

  @derive(eqv, show, encoder, decoder)
  case class AdmissionVote(
    targetPeer: PeerId,
    reason: AdmissionReason,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash
  ) extends PeerDeclaration

  object AdmissionVote {
    implicit val ordering: Ordering[AdmissionVote] =
      Ordering.by { v =>
        (v.targetPeer.value.value, v.reason.toString, v.facilitatorsHash.value, v.lastSnapshotHash.value)
      }
    implicit val order: cats.kernel.Order[AdmissionVote] = cats.kernel.Order.fromOrdering(ordering)
  }

  // Explicit codecs for `Signed[AdmissionVote]` — same rationale as the VCV/EV codecs above.
  implicit val signedAdmissionVoteEncoder: Encoder[Signed[AdmissionVote]] =
    Encoder.instance { sv =>
      Json.obj("value" -> sv.value.asJson, "proofs" -> sv.proofs.asJson)
    }
  implicit val signedAdmissionVoteDecoder: Decoder[Signed[AdmissionVote]] =
    (c: HCursor) =>
      for {
        value <- c.downField("value").as[AdmissionVote]
        proofs <- c.downField("proofs").as[NonEmptySet[SignatureProof]]
      } yield Signed(value, proofs)

  implicit val admissionVotesEncoder: Encoder[NonEmptySet[Signed[AdmissionVote]]] =
    NonEmptySetCodec.encoder[Signed[AdmissionVote]]
  implicit val admissionVotesDecoder: Decoder[NonEmptySet[Signed[AdmissionVote]]] =
    NonEmptySetCodec.decoder[Signed[AdmissionVote]]

  // `lastSnapshotHash` binds the cert to a specific tip -- see
  // EvictionCertificate above for the same rationale. Without it, a stale quorum of signed
  // admission votes at an older tip could be replayed as if fresh.
  @derive(eqv, show, encoder, decoder)
  case class AdmissionCertificate(
    targetPeer: PeerId,
    reason: AdmissionReason,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash,
    votes: NonEmptySet[Signed[AdmissionVote]]
  )

  object AdmissionCertificate {

    /** Count distinct voter PeerIds, not Signed wrapper instances. A malicious certificate can carry multiple differently encoded votes
      * from one signer; proposal validation must not treat those as independent quorum members.
      */
    def uniqueVoterCount(certificate: AdmissionCertificate): Int =
      certificate.votes.toNonEmptyList.toList.map(_.proofs.head.id.toPeerId).toSet.size

    implicit val ordering: Ordering[AdmissionCertificate] =
      Ordering.by { c =>
        (c.targetPeer.value.value, c.reason.toString, c.facilitatorsHash.value, c.lastSnapshotHash.value)
      }
    implicit val order: cats.kernel.Order[AdmissionCertificate] = cats.kernel.Order.fromOrdering(ordering)
  }

  @derive(eqv, show, encoder, decoder)
  case class Proposal(
    hash: Hash,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash,
    view: Long,
    vcc: Option[ViewChangeCertificate],
    timeoutCertificate: Option[TimeoutCertificate] = None,
    // Phase B1 facilitator shrinkage: quorum-certified eviction votes for persistently-absent
    // peers, accumulated and certified before this proposal. Must be sorted for deterministic
    // proposal-hash agreement across nodes (enforced at the proposer call site via
    // `EvictionCertificate.ordering`). Empty list is the overwhelmingly common case.
    // Defaults to empty so old on-disk outcomes (written before B1) round-trip cleanly.
    evictionCertificates: List[EvictionCertificate] = List.empty,
    // Phase B2 re-admission: quorum-certified readmission votes for previously-removed
    // peers that are now observed at tip. Applied at the advancer: removes the target
    // from `state.readmissionCountdown` (if present) and returns them to
    // `state.eligibleFacilitators`. Same sorting + determinism requirements as
    // evictionCertificates. Defaults empty for forward compatibility — old proposals
    // written before B2 round-trip with an empty admission list.
    admissionCertificates: List[AdmissionCertificate] = List.empty,
    // v7 (flaky-byzantine threat model): leader's positive observation of which
    // round-start facilitators sent a Facility declaration during this round's
    // facility-collection window. Bound by the leader's signed rumor envelope
    // (RumorValidator.scala:50 — signers.contains(rumor.origin)). Followers
    // validate that observedResponders ⊆ roundStartFacilitators. Consumed at
    // round-finalize to update lastOutcome.peerQuality with positive
    // participation evidence — fixes the v3-codex-flagged "silent peers score
    // (1,1)" blindness where any non-fork-evicted facilitator was credited
    // regardless of whether they actually sent a Facility. List[PeerId] sorted
    // at proposal-build time (mirrors evictionCertificates / admissionCertificates
    // sort-at-construction pattern) for deterministic proposal-hash agreement.
    // Defaults empty for old-format compatibility (cold-restart hard fork in
    // practice).
    observedResponders: List[PeerId] = List.empty,
    // Self-health throttle: leader's canonical view of each observed
    // responder's `SelfHealthHint`, aggregated from the Facilities collected this round.
    // Signed into the Proposal so all followers adopt the same map on accept; this is what
    // makes the hint consensus-agreed for the next round's `selectLeaderWeighted`. Peers
    // absent from the map default to `Healthy` at read time.
    //
    // `SortedMap` (not `Map`) so circe's derived encoder iterates by `PeerId` order and
    // produces byte-identical JSON across nodes -- a `Map` (HashMap-backed) iterator order
    // is hash-bucket-dependent and would diverge between leaders running on different JVMs
    // or with different mutation histories, breaking the proposal-hash quorum check.
    observedSelfHealth: SortedMap[PeerId, SelfHealthHint] = SortedMap.empty,
    // Canonical open-admission nomination for the NEXT round. The leader chooses one
    // entropy-ranked peer from the candidate advertisements in the Facilities it collected;
    // followers adopt this exact value on Proposal acceptance. Carrying one nominee avoids
    // asking next-round voters to rank node-local candidate universes, which can differ at
    // quorum-crossing time. None is the backward-compatible/pre-upgrade value.
    admissionNominee: Option[PeerId] = None
  ) extends PeerDeclaration

  @derive(eqv, show, encoder, decoder)
  case class MajoritySignature(
    signature: Signature,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash,
    view: Long,
    proposalHash: Hash
  ) extends PeerDeclaration

  @derive(eqv, show, encoder, decoder)
  case class BinarySignature(signature: Signature, facilitatorsHash: Hash, lastSnapshotHash: Hash) extends PeerDeclaration

}
