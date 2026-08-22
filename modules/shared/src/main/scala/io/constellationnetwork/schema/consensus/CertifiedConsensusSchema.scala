package io.constellationnetwork.schema.consensus

import cats.Show
import cats.data.NonEmptySet
import cats.syntax.eq._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency.SnapshotFee
import io.constellationnetwork.schema.ConsensusOperationalState
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.signature.SignatureProof

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe._
import io.circe.syntax._

object CertifiedConsensusSchema {
  val Version: Int = 35
}

/** Wire-only v35 types shared by DAG L0, Currency L0, public snapshot schemas and Snapshot
  * Streaming. Construction and verification remain in node-shared `CertifiedConsensus`.
  */
@derive(eqv, show)
sealed trait ConsensusDomain extends Product with Serializable {
  def entryName: String
}

object ConsensusDomain {
  case object DagL0 extends ConsensusDomain { val entryName: String = "dag-l0" }
  case object CurrencyL0 extends ConsensusDomain { val entryName: String = "currency-l0" }

  implicit val encoder: Encoder[ConsensusDomain] = Encoder.encodeString.contramap(_.entryName)
  implicit val decoder: Decoder[ConsensusDomain] = Decoder.decodeString.emap {
    case DagL0.entryName      => Right(DagL0)
    case CurrencyL0.entryName => Right(CurrencyL0)
    case other                => Left(s"Unknown consensus certification domain: $other")
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
  roundStartCore: NonEmptySet[PeerId],
  roundStartCoreHash: Hash,
  committedView: Long,
  trigger: ConsensusTrigger,
  admissionNominee: Option[PeerId],
  admittedPeers: SortedSet[PeerId],
  evictedPeers: SortedSet[PeerId],
  observedResponders: SortedSet[PeerId],
  observedSelfHealth: SortedMap[PeerId, SelfHealthHint],
  timeoutVoters: SortedSet[PeerId],
  consensusEndTime: Option[Long]
)

object ProposalValue {
  implicit val showInstance: Show[ProposalValue] = Show.fromToString

  def validate(value: ProposalValue): Either[String, Unit] = {
    val fullCommittee = value.roundStartFacilitators.toSortedSet
    val core = value.roundStartCore.toSortedSet

    for {
      _ <- Either.cond(
        value.schemaVersion === CertifiedConsensusSchema.Version,
        (),
        s"schema_version:${value.schemaVersion}"
      )
      _ <- Either.cond(value.networkId.nonEmpty, (), "network_id_empty")
      _ <- Either.cond(value.key >= 0L, (), "key_negative")
      _ <- Either.cond(value.committedView >= 0L, (), "committed_view_negative")
      _ <- Either.cond(core.subsetOf(fullCommittee), (), "round_start_core_not_subset")
      _ <- Either.cond(value.admittedPeers.intersect(value.evictedPeers).isEmpty, (), "admit_evict_overlap")
      _ <- Either.cond(value.observedResponders.subsetOf(fullCommittee), (), "responders_not_subset")
      _ <- Either.cond(
        value.observedSelfHealth.keySet.subsetOf(value.observedResponders),
        (),
        "self_health_not_responder_subset"
      )
      _ <- Either.cond(value.consensusEndTime.forall(_ >= 0L), (), "consensus_end_time_negative")
    } yield ()
  }
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
  roundStartCoreHash: Hash,
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

@derive(eqv)
final case class CoreCommitQC(
  valueHash: Hash,
  roundStartCoreHash: Hash,
  signatures: NonEmptySet[SignatureProof]
)

object CoreCommitQC {
  implicit val showInstance: Show[CoreCommitQC] = Show.fromToString
  implicit val encoder: Encoder[CoreCommitQC] = Encoder.instance { qc =>
    Json.obj(
      "valueHash" -> qc.valueHash.asJson,
      "roundStartCoreHash" -> qc.roundStartCoreHash.asJson,
      "signatures" -> qc.signatures.asJson
    )
  }
  implicit val decoder: Decoder[CoreCommitQC] = (c: HCursor) =>
    for {
      valueHash <- c.downField("valueHash").as[Hash]
      roundStartCoreHash <- c.downField("roundStartCoreHash").as[Hash]
      signatures <- c.downField("signatures").as[NonEmptySet[SignatureProof]]
    } yield CoreCommitQC(valueHash, roundStartCoreHash, signatures)
}

@derive(eqv, encoder, decoder)
final case class CertifiedOutcome(
  proposalQc: CertifiedProposalQC,
  coreCommitQc: CoreCommitQC
)

object CertifiedOutcome {
  implicit val showInstance: Show[CertifiedOutcome] = Show.fromToString
}

/** Layer evidence for a child-carried parent certificate. Currency carries only the fields that
  * cannot be reconstructed from the already-held public signed parent artifact. Carrying the
  * entire StateChannelSnapshotBinary is forbidden: its content embeds that artifact and would
  * recursively embed the full lineage.
  */
@derive(eqv, show, encoder, decoder)
sealed trait CertifiedLayerEvidenceV1

object CertifiedLayerEvidenceV1 {
  @derive(eqv, show, encoder, decoder)
  final case class Currency(
    parentBinaryLastSnapshotHash: Hash,
    parentBinaryFee: SnapshotFee,
    parentBinaryProofs: NonEmptySet[SignatureProof]
  ) extends CertifiedLayerEvidenceV1
}

@derive(eqv, show, encoder, decoder)
final case class CertifiedLineageEvidenceV1(
  parentOutcome: CertifiedOutcome,
  parentLayerEvidence: Option[CertifiedLayerEvidenceV1]
)

/** Layer-specific continuation state installed from an independently authorized full checkpoint.
  *
  * Unlike [[CertifiedLayerEvidenceV1]], this is not a proof envelope for reconstructing an incremental artifact that the verifier already
  * holds. A compacted full Currency snapshot does not contain the exact source incremental bytes, so its historical binary cannot be
  * reconstructed from the full snapshot. The containing full-snapshot hash is the external authority; `lastBinaryHash` is the minimal
  * state needed to validate the next Currency binary's parent link. When source incremental history is available, checkpoint construction
  * must still verify this value against that history before publication.
  */
@derive(eqv, show, encoder, decoder)
sealed trait CertifiedCheckpointLayerStateV1

object CertifiedCheckpointLayerStateV1 {
  case object Dag extends CertifiedCheckpointLayerStateV1

  @derive(eqv, show, encoder, decoder)
  final case class Currency(lastBinaryHash: Hash) extends CertifiedCheckpointLayerStateV1
}

/** Minimal derived state carried by an operator-authorized full-snapshot checkpoint.
  *
  * This object does not authenticate its containing full snapshot. Authority comes only from
  * the separately announced containing-snapshot hash; `certifiedTip` proves continuity with the
  * source incremental tip.
  */
@derive(eqv, encoder, decoder)
final case class CertifiedCheckpointV1(
  certifiedTip: CertifiedOutcome,
  nextRoundFacilitators: NonEmptySet[PeerId],
  operationalState: ConsensusOperationalState,
  peerSelfHealth: SortedMap[PeerId, SelfHealthHint],
  expandedBeyondSingleton: Boolean,
  layerState: CertifiedCheckpointLayerStateV1
)

object CertifiedCheckpointV1 {
  // Avoid the repository's two intentionally retained SortedMap Show orphans
  // becoming ambiguous during Magnolia derivation.
  implicit val showInstance: Show[CertifiedCheckpointV1] = Show.fromToString
}
