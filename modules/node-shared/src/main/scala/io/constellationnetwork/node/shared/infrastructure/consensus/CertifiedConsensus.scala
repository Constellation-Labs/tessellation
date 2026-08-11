package io.constellationnetwork.node.shared.infrastructure.consensus

import java.security.KeyPair

import cats.Show
import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.infrastructure.consensus.state.QuorumPolicy
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.SignatureProof
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe._
import io.circe.syntax._

/** V35 certification primitives shared by DAG L0 and Currency L0.
  *
  * Snapshot artifact signatures deliberately keep their existing bare-artifact-hash meaning. These types add a separate Core certificate
  * over every semantic input that can affect the persisted consensus outcome. All signing and verification goes through the repository's
  * standard `Signed` + `Hasher` infrastructure; this module only defines the typed payload and quorum policy.
  */
object CertifiedConsensus {

  val SchemaVersion: Int = 35

  // Resolve the same orphan-instance ambiguity documented for Proposal in declaration.scala.
  implicit val showSortedSelfHealth: Show[SortedMap[PeerId, SelfHealthHint]] =
    Show.show(_.toList.map { case (peerId, hint) => s"${peerId.show}->${hint.show}" }.mkString("{", ",", "}"))

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

  /** Prepare and commit are separate signature domains while sharing one generic statement and the normal Signed infrastructure. */
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

  /** The complete view-independent semantic value certified by Core.
    *
    * Collection types encode their canonical ordering in the type itself. There is intentionally no caller-visible normalization step:
    * callers cannot construct an unordered committee or responder set and accidentally hash it.
    */
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
        _ <- Either.cond(value.schemaVersion === SchemaVersion, (), s"schema_version:${value.schemaVersion}")
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

  /** Generic domain-separated statement signed with `Signed.forAsyncHasher`. The purpose field prevents prepare proofs from being replayed
    * as commit proofs, while the domain/network/key/parent fields prevent cross-layer, cross-network, and cross-round replay.
    */
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

  type OutcomeVote = Signed[CertificationStatement]
  type CoreCommit = Signed[CertificationStatement]

  /** A prepare QC embeds the complete semantic value. View-change voters can therefore transfer/re-propose it without relying on a local
    * artifact/proposal cache.
    */
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

  def valueHash[F[_]: Hasher](value: ProposalValue): F[Hash] =
    Hasher[F].hash(value)

  def requiredCoreQuorum(coreSize: Int, configuredFraction: Double): Int =
    math.max(QuorumPolicy.supermajority(coreSize), QuorumPolicy.fromFraction(coreSize, configuredFraction))

  private def statement(purpose: CertificationPurpose, value: ProposalValue, hash: Hash): CertificationStatement =
    CertificationStatement(
      purpose,
      value.schemaVersion,
      value.domain,
      value.networkId,
      value.key,
      value.parentArtifactHash,
      hash,
      value.roundStartFacilitatorsHash,
      value.roundStartCoreHash,
      value.committedView
    )

  def signOutcomeVote[F[_]: Async: Hasher: SecurityProvider](
    value: ProposalValue,
    keyPair: KeyPair
  ): F[(Hash, OutcomeVote)] =
    for {
      hash <- valueHash(value)
      signed <- Signed.forAsyncHasher[F, CertificationStatement](statement(CertificationPurpose.Prepare, value, hash), keyPair)
    } yield hash -> signed

  def signCoreCommit[F[_]: Async: Hasher: SecurityProvider](qc: CertifiedProposalQC, keyPair: KeyPair): F[CoreCommit] =
    Signed.forAsyncHasher[F, CertificationStatement](
      statement(CertificationPurpose.Commit, qc.value, qc.valueHash),
      keyPair
    )

  private def candidateProofs(
    expected: CertificationStatement,
    votes: SortedMap[PeerId, Signed[CertificationStatement]],
    frozenCore: Set[PeerId],
    requiredQuorum: Int
  ): Either[String, NonEmptySet[SignatureProof]] = {
    val proofs = votes.toList.collect {
      case (peerId, signed)
          if frozenCore.contains(peerId) &&
            signed.value === expected &&
            signed.proofs.size === 1L &&
            signed.proofs.head.id.toPeerId === peerId =>
        signed.proofs.head
    }
    val signers = proofs.map(_.id.toPeerId)

    for {
      _ <- Either.cond(signers.distinct.size === signers.size, (), "duplicate_core_signer")
      _ <- Either.cond(signers.toSet.subsetOf(frozenCore), (), "signer_outside_frozen_core")
      _ <- Either.cond(signers.size >= requiredQuorum, (), s"core_under_quorum:${signers.size}/$requiredQuorum")
      nonEmpty <- NonEmptySet.fromSet(SortedSet.from(proofs)).toRight("empty_core_proofs")
    } yield nonEmpty
  }

  private def verifyProofs[F[_]: Async: Hasher: SecurityProvider](
    expected: CertificationStatement,
    proofs: NonEmptySet[SignatureProof],
    frozenCore: Set[PeerId],
    requiredQuorum: Int
  ): F[Either[String, Unit]] = {
    val signers = proofs.toSortedSet.toList.map(_.id.toPeerId)
    val structure = for {
      _ <- Either.cond(signers.distinct.size === signers.size, (), "duplicate_core_signer")
      _ <- Either.cond(signers.toSet.subsetOf(frozenCore), (), "signer_outside_frozen_core")
      _ <- Either.cond(signers.size >= requiredQuorum, (), s"core_under_quorum:${signers.size}/$requiredQuorum")
    } yield ()

    structure match {
      case Left(error) => error.asLeft[Unit].pure[F]
      case Right(_) =>
        Signed(expected, proofs).hasValidSignature[F].map(Either.cond(_, (), "invalid_certification_signature"))
    }
  }

  def buildProposalQc[F[_]: Async: Hasher: SecurityProvider](
    value: ProposalValue,
    votes: SortedMap[PeerId, OutcomeVote],
    frozenCore: Set[PeerId],
    configuredFraction: Double
  ): F[Either[String, CertifiedProposalQC]] =
    valueHash(value).flatMap { hash =>
      val expected = statement(CertificationPurpose.Prepare, value, hash)
      val required = requiredCoreQuorum(frozenCore.size, configuredFraction)

      (ProposalValue.validate(value), candidateProofs(expected, votes, frozenCore, required)).mapN((_, proofs) => proofs) match {
        case Left(error) => error.asLeft[CertifiedProposalQC].pure[F]
        case Right(proofs) =>
          verifyProofs(expected, proofs, frozenCore, required).map(_.map(_ => CertifiedProposalQC(value, hash, proofs)))
      }
    }

  def buildCoreCommitQc[F[_]: Async: Hasher: SecurityProvider](
    proposalQc: CertifiedProposalQC,
    commits: SortedMap[PeerId, CoreCommit],
    frozenCore: Set[PeerId],
    configuredFraction: Double
  ): F[Either[String, CoreCommitQC]] = {
    val expected = statement(CertificationPurpose.Commit, proposalQc.value, proposalQc.valueHash)
    val required = requiredCoreQuorum(frozenCore.size, configuredFraction)

    candidateProofs(expected, commits, frozenCore, required) match {
      case Left(error) => error.asLeft[CoreCommitQC].pure[F]
      case Right(proofs) =>
        verifyProofs(expected, proofs, frozenCore, required).map(
          _.map(_ => CoreCommitQC(proposalQc.valueHash, proposalQc.value.roundStartCoreHash, proofs))
        )
    }
  }

  def verifyProposalQc[F[_]: Async: Hasher: SecurityProvider](
    qc: CertifiedProposalQC,
    frozenCore: Set[PeerId],
    configuredFraction: Double
  ): F[Either[String, Unit]] =
    valueHash(qc.value).flatMap { recomputed =>
      val structure = ProposalValue
        .validate(qc.value)
        .productL(Either.cond(recomputed === qc.valueHash, (), "value_hash_mismatch"))

      structure match {
        case Left(error) => error.asLeft[Unit].pure[F]
        case Right(_) =>
          verifyProofs(
            statement(CertificationPurpose.Prepare, qc.value, qc.valueHash),
            qc.signatures,
            frozenCore,
            requiredCoreQuorum(frozenCore.size, configuredFraction)
          )
      }
    }

  def verifyCoreCommitQc[F[_]: Async: Hasher: SecurityProvider](
    proposalQc: CertifiedProposalQC,
    commitQc: CoreCommitQC,
    frozenCore: Set[PeerId],
    configuredFraction: Double
  ): F[Either[String, Unit]] = {
    val structure = for {
      _ <- Either.cond(commitQc.valueHash === proposalQc.valueHash, (), "commit_value_hash_mismatch")
      _ <- Either.cond(
        commitQc.roundStartCoreHash === proposalQc.value.roundStartCoreHash,
        (),
        "commit_core_hash_mismatch"
      )
    } yield ()

    structure match {
      case Left(error) => error.asLeft[Unit].pure[F]
      case Right(_) =>
        verifyProofs(
          statement(CertificationPurpose.Commit, proposalQc.value, proposalQc.valueHash),
          commitQc.signatures,
          frozenCore,
          requiredCoreQuorum(frozenCore.size, configuredFraction)
        )
    }
  }
}
