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

  @derive(eqv, show, encoder, decoder)
  case class Facility(
    eventHashes: Set[Hash],
    candidates: Candidates,
    trigger: Option[ConsensusTrigger],
    facilitatorsHash: Hash,
    lastGlobalSnapshotOrdinal: SnapshotOrdinal,
    lastSnapshotHash: Hash,
    consensusConfigHash: Option[Hash] = None
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

  @derive(eqv, show, encoder, decoder)
  case class EvictionCertificate(
    targetPeer: PeerId,
    reason: EvictionReason,
    facilitatorsHash: Hash,
    votes: NonEmptySet[Signed[EvictionVote]]
  )

  object EvictionCertificate {
    implicit val ordering: Ordering[EvictionCertificate] =
      Ordering.by { c =>
        (c.targetPeer.value.value, c.reason.toString, c.facilitatorsHash.value)
      }
    implicit val order: cats.kernel.Order[EvictionCertificate] = cats.kernel.Order.fromOrdering(ordering)
  }

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
    evictionCertificates: List[EvictionCertificate] = List.empty
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
