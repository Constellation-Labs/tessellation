package io.constellationnetwork.schema.consensus

import cats.Show
import cats.data.NonEmptySet
import cats.syntax.eq._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.signature.SignatureProof

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe._
import io.circe.syntax._

object CertifiedConsensusSchema {
  val Version: Int = 36
}

/** Wire-only v35 types used by DAG L0, the Global incremental snapshot schema, and Snapshot Streaming. Construction and verification remain
  * in node-shared `CertifiedConsensus`. Currency L0 deliberately uses its Currency-local flat synchronous protocol and does not construct
  * or consume these certificates.
  */
@derive(eqv, show)
sealed trait ConsensusDomain extends Product with Serializable {
  def entryName: String
}

object ConsensusDomain {
  case object DagL0 extends ConsensusDomain { val entryName: String = "dag-l0" }

  implicit val encoder: Encoder[ConsensusDomain] = Encoder.encodeString.contramap(_.entryName)
  implicit val decoder: Decoder[ConsensusDomain] = Decoder.decodeString.emap {
    case DagL0.entryName => Right(DagL0)
    case other           => Left(s"Unknown consensus certification domain: $other")
  }
}

@derive(eqv, show)
sealed trait CertificationPurpose extends Product with Serializable {
  def entryName: String
}

object CertificationPurpose {
  case object Prepare extends CertificationPurpose { val entryName: String = "outcome-prepare-v35" }
  case object Commit extends CertificationPurpose { val entryName: String = "outcome-commit-v35" }

  implicit val encoder: Encoder[CertificationPurpose] = Encoder.encodeString.contramap(_.entryName)
  implicit val decoder: Decoder[CertificationPurpose] = Decoder.decodeString.emap {
    case Prepare.entryName => Right(Prepare)
    case Commit.entryName  => Right(Commit)
    case other             => Left(s"Unknown consensus certification purpose: $other")
  }
}

@derive(eqv, show)
sealed trait TriggerStatementPurpose extends Product with Serializable {
  def entryName: String
}

object TriggerStatementPurpose {
  case object Facility extends TriggerStatementPurpose { val entryName: String = "facility-trigger-v35" }

  implicit val encoder: Encoder[TriggerStatementPurpose] = Encoder.encodeString.contramap(_.entryName)
  implicit val decoder: Decoder[TriggerStatementPurpose] = Decoder.decodeString.emap {
    case Facility.entryName => Right(Facility)
    case other              => Left(s"Unknown trigger statement purpose: $other")
  }
}

@derive(eqv, show, encoder, decoder)
final case class TriggerStatement(
  purpose: TriggerStatementPurpose,
  schemaVersion: Int,
  domain: ConsensusDomain,
  networkId: String,
  key: Long,
  parentArtifactHash: Hash,
  roundStartFacilitatorsHash: Hash,
  consensusConfigHash: Hash,
  trigger: Option[ConsensusTrigger]
)

@derive(eqv, encoder, decoder)
final case class CertifiedRoundAuthorityV1(
  facilitators: NonEmptySet[PeerId],
  // Signed redundant binding, not an advisory cache. Every ProposalQC construction and verification recomputes it
  // from the carried set.
  facilitatorsHash: Hash
)

@derive(eqv, encoder, decoder)
final case class ProposalValue(
  schemaVersion: Int,
  domain: ConsensusDomain,
  networkId: String,
  key: Long,
  parentArtifactHash: Hash,
  artifactHash: Hash,
  contextHash: Hash,
  roundStartFacilitators: NonEmptySet[PeerId],
  roundStartFacilitatorsHash: Hash,
  // The current QC authenticates the exact voting committee allowed to
  // certify the next round. Historical replay consumes this transition effect
  // directly instead of re-running a joiner's current policy against old data.
  nextRoundAuthority: CertifiedRoundAuthorityV1,
  // Standard Hasher commitment to the existing post-round
  // ConsensusOperationalState. The terminal sidecar supplies the preimage;
  // binding it here prevents peer-local controller state from becoming
  // unauthenticated initialization input without duplicating that state in the
  // public lineage.
  nextOperationalStateHash: Hash,
  committedView: Long,
  trigger: ConsensusTrigger,
  admissionNominee: Option[PeerId],
  admittedPeers: SortedSet[PeerId],
  evictedPeers: SortedSet[PeerId],
  timeoutVoters: SortedSet[PeerId],
  consensusEndTime: Option[Long]
)

object ProposalValue {
  implicit val showInstance: Show[ProposalValue] = Show.fromToString

  def validate(value: ProposalValue): Either[String, Unit] =
    for {
      _ <- Either.cond(
        value.schemaVersion === CertifiedConsensusSchema.Version,
        (),
        s"schema_version:${value.schemaVersion}"
      )
      _ <- Either.cond(value.networkId.nonEmpty, (), "network_id_empty")
      _ <- Either.cond(value.key >= 0L, (), "key_negative")
      _ <- Either.cond(value.committedView >= 0L, (), "committed_view_negative")
      _ <- Either.cond(value.admittedPeers.intersect(value.evictedPeers).isEmpty, (), "admit_evict_overlap")
      _ <- Either.cond(value.consensusEndTime.forall(_ >= 0L), (), "consensus_end_time_negative")
    } yield ()
}

@derive(eqv, show, encoder, decoder)
final case class CertificationStatement(
  purpose: CertificationPurpose,
  schemaVersion: Int,
  domain: ConsensusDomain,
  networkId: String,
  key: Long,
  parentArtifactHash: Hash,
  valueHash: Hash,
  roundStartFacilitatorsHash: Hash,
  certifiedView: Long
)

@derive(eqv)
final case class CertifiedProposalQC(
  value: ProposalValue,
  valueHash: Hash,
  signatures: NonEmptySet[SignatureProof]
)

object CertifiedProposalQC {
  implicit val showInstance: Show[CertifiedProposalQC] = Show.fromToString
  implicit val encoder: Encoder[CertifiedProposalQC] = Encoder.instance { qc =>
    Json.obj("value" -> qc.value.asJson, "valueHash" -> qc.valueHash.asJson, "signatures" -> qc.signatures.asJson)
  }
  implicit val decoder: Decoder[CertifiedProposalQC] = (c: HCursor) =>
    for {
      value <- c.downField("value").as[ProposalValue]
      valueHash <- c.downField("valueHash").as[Hash]
      signatures <- c.downField("signatures").as[NonEmptySet[SignatureProof]]
    } yield CertifiedProposalQC(value, valueHash, signatures)
}

/** Commit quorum certificate over a proposal value, signed by the frozen voting committee. */
@derive(eqv)
final case class CommitQC(
  valueHash: Hash,
  signatures: NonEmptySet[SignatureProof]
)

object CommitQC {
  implicit val showInstance: Show[CommitQC] = Show.fromToString
  implicit val encoder: Encoder[CommitQC] = Encoder.instance { qc =>
    Json.obj("valueHash" -> qc.valueHash.asJson, "signatures" -> qc.signatures.asJson)
  }
  implicit val decoder: Decoder[CommitQC] = (c: HCursor) =>
    for {
      valueHash <- c.downField("valueHash").as[Hash]
      signatures <- c.downField("signatures").as[NonEmptySet[SignatureProof]]
    } yield CommitQC(valueHash, signatures)
}

@derive(eqv, encoder, decoder)
final case class CertifiedOutcome(
  proposalQc: CertifiedProposalQC,
  commitQc: CommitQC
)

object CertifiedOutcome {
  implicit val showInstance: Show[CertifiedOutcome] = Show.fromToString
}

@derive(eqv, show, encoder, decoder)
final case class CertifiedLineageEvidenceV1(
  parentOutcome: CertifiedOutcome
)
