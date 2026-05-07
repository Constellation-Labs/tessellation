package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.NonEmptySet

import io.constellationnetwork.ext.codecs.NonEmptySetCodec
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
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

  @derive(eqv, show, encoder, decoder)
  sealed trait PeerDeclaration {
    def facilitatorsHash: Hash
    def lastSnapshotHash: Hash
  }

  // Facility is declared LATER in this file (after EvictionCertificate / AdmissionCertificate
  // and before Proposal) because it embeds `appliedEvictionCerts: List[EvictionCertificate]`
  // for the same circe-generic forward-reference reason as Proposal/VCC documented below.

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

  // Codex review 2026-04-23: `lastSnapshotHash` field binds the cert to a specific tip.
  // Without it, a leader could replay an older quorum of signed votes that matched the
  // current `facilitatorsHash` but referenced a stale tip — followers would accept it.
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
  // Codex-approved design (2026-04-23) — mirrors B1 eviction-cert semantics. The
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

  // Codex review 2026-04-23: `lastSnapshotHash` binds the cert to a specific tip — see
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
    implicit val ordering: Ordering[AdmissionCertificate] =
      Ordering.by { c =>
        (c.targetPeer.value.value, c.reason.toString, c.facilitatorsHash.value, c.lastSnapshotHash.value)
      }
    implicit val order: cats.kernel.Order[AdmissionCertificate] = cats.kernel.Order.fromOrdering(ordering)
  }

  // Facility is declared HERE (rather than at the top with the other PeerDeclarations) so its
  // `appliedEvictionCerts: List[EvictionCertificate]` field can resolve the circe-generic
  // implicit chain at macro-expansion time. Same forward-reference rationale as
  // Proposal-after-ViewChangeCertificate.
  @derive(eqv, show, encoder, decoder)
  case class Facility(
    eventHashes: Set[Hash],
    candidates: Candidates,
    trigger: Option[ConsensusTrigger],
    facilitatorsHash: Hash,
    lastGlobalSnapshotOrdinal: SnapshotOrdinal,
    lastSnapshotHash: Hash,
    consensusConfigHash: Option[Hash] = None,
    // Quorum-witnessed eviction certificates this node is applying at round-start. Used to
    // shrink the round committee for the SAME ordinal that produced the cert — closes the
    // testnet 2026-05-07 ord 3121304 stuck-cluster gap where certs assembled but never took
    // effect because no Proposal was ever accepted.
    //
    // Determinism rule: this list participates in fork detection / quorum grouping at
    // facility-collection time. A round only advances when quorum-many Facilities agree on
    // (facilitatorsHash, sorted_appliedEvictionCert_targets, lastSnapshotHash, consensusConfigHash).
    // See `docs/consensus/eviction-cert-deterministic-shrinkage.md`.
    //
    // Sort by `EvictionCertificate.ordering` (by target peer id, then reason, then hashes) at
    // construction time so two nodes with the same applied-cert SET produce byte-identical
    // serializations and matching tuple-quorum membership.
    //
    // Defaults to empty for forward compatibility — old peers (pre-PR2) and rounds where no
    // cert has been assembled write out an empty list.
    appliedEvictionCerts: List[EvictionCertificate] = List.empty
  ) extends PeerDeclaration

  @derive(eqv, show, encoder, decoder)
  case class Proposal(
    hash: Hash,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash,
    view: Long,
    vcc: Option[ViewChangeCertificate],
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
    observedResponders: List[PeerId] = List.empty
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
