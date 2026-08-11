package io.constellationnetwork.node.shared.infrastructure.consensus

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.all._
import cats.{Applicative, Show}

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration.FiniteDuration

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

  /** Verifiable consensus evidence persisted beside the ordinary finished outcome.
    *
    * The public artifact and its existing proofs stay in the layer-specific `Finished` value. This sidecar only adds the two Core
    * certificates, and is shared by DAG L0 and Currency L0.
    */
  @derive(eqv, encoder, decoder)
  final case class CertifiedOutcome(
    proposalQc: CertifiedProposalQC,
    coreCommitQc: CoreCommitQC
  )

  object CertifiedOutcome {
    implicit val showInstance: Show[CertifiedOutcome] = Show.fromToString
  }

  def valueHash[F[_]: Hasher](value: ProposalValue): F[Hash] =
    Hasher[F].hash(value)

  /** Construct the common semantic value for either L0 layer.
    *
    * Layer code supplies its typed context and domain; extraction of shared Proposal evidence and canonical collection handling live in one
    * place so DAG and Currency cannot drift.
    */
  def proposalValue[F[_]: Applicative: Hasher, Context: Encoder](
    domain: ConsensusDomain,
    networkId: String,
    key: Long,
    parentArtifactHash: Hash,
    artifactHash: Hash,
    context: Context,
    roundStartFacilitators: NonEmptySet[PeerId],
    roundStartCore: NonEmptySet[PeerId],
    committedView: Long,
    trigger: ConsensusTrigger,
    proposal: declaration.Proposal,
    consensusEndTime: Option[Long]
  ): F[ProposalValue] = {
    val timeoutVoters = proposal.timeoutCertificate
      .fold(SortedSet.empty[PeerId])(tc => SortedSet.from(tc.votes.toNonEmptyList.toList.map(_.proofs.head.id.toPeerId)))

    (
      Hasher[F].hash(context),
      Hasher[F].hash(roundStartFacilitators),
      Hasher[F].hash(roundStartCore)
    ).mapN { (contextHash, fullHash, coreHash) =>
      ProposalValue(
        schemaVersion = SchemaVersion,
        domain = domain,
        networkId = networkId,
        key = key,
        parentArtifactHash = parentArtifactHash,
        artifactHash = artifactHash,
        contextHash = contextHash,
        roundStartFacilitators = roundStartFacilitators,
        roundStartFacilitatorsHash = fullHash,
        roundStartCore = roundStartCore,
        roundStartCoreHash = coreHash,
        committedView = committedView,
        trigger = trigger,
        admissionNominee = proposal.admissionNominee,
        admittedPeers = SortedSet.from(proposal.admissionCertificates.map(_.targetPeer)),
        evictedPeers = SortedSet.from(proposal.evictionCertificates.map(_.targetPeer)),
        observedResponders = SortedSet.from(proposal.observedResponders),
        observedSelfHealth = proposal.observedSelfHealth,
        timeoutVoters = timeoutVoters,
        consensusEndTime = consensusEndTime
      )
    }
  }

  /** Re-derive only the layer/round identity of an already-certified value.
    *
    * A later view has a different VCC/TC transport envelope, so rebuilding semantic fields from that envelope would incorrectly mutate the
    * locked value. The QC remains the source of semantic fields; this helper recomputes every locally verifiable identity/hash field with
    * the same standard Hasher used for a fresh value. Comparing the result to the carried value catches wrong-layer, wrong-parent,
    * wrong-artifact, wrong-context, and wrong-committee replay without inventing a second encoding scheme.
    */
  def rederiveCertifiedValue[F[_]: Applicative: Hasher, Context: Encoder](
    certified: ProposalValue,
    domain: ConsensusDomain,
    networkId: String,
    key: Long,
    parentArtifactHash: Hash,
    artifactHash: Hash,
    context: Context,
    roundStartFacilitators: NonEmptySet[PeerId],
    roundStartCore: NonEmptySet[PeerId]
  ): F[ProposalValue] =
    (
      Hasher[F].hash(context),
      Hasher[F].hash(roundStartFacilitators),
      Hasher[F].hash(roundStartCore)
    ).mapN { (contextHash, fullHash, coreHash) =>
      certified.copy(
        schemaVersion = SchemaVersion,
        domain = domain,
        networkId = networkId,
        key = key,
        parentArtifactHash = parentArtifactHash,
        artifactHash = artifactHash,
        contextHash = contextHash,
        roundStartFacilitators = roundStartFacilitators,
        roundStartFacilitatorsHash = fullHash,
        roundStartCore = roundStartCore,
        roundStartCoreHash = coreHash
      )
    }

  def requiredCoreQuorum(coreSize: Int, configuredFraction: Double): Int =
    math.max(QuorumPolicy.supermajority(coreSize), QuorumPolicy.fromFraction(coreSize, configuredFraction))

  /** Select whether this node may emit a pacemaker vote and, when it may, the exact direct-gossip targets.
    *
    * Legacy rounds retain their existing active-facilitator behavior. Certified rounds use only frozen Core votes for VCC/TC quorum
    * intersection, while delivering those votes to the complete frozen committee. `None` distinguishes an ineligible Tier-1 node from an
    * eligible solo Core node whose target set is legitimately empty.
    */
  def pacemakerVoteTargets(
    certifiedConsensusActive: Boolean,
    selfId: PeerId,
    frozenCommittee: Set[PeerId],
    frozenCore: Set[PeerId],
    legacyFacilitators: Set[PeerId]
  ): Option[Set[PeerId]] =
    if (certifiedConsensusActive && !frozenCore.contains(selfId)) None
    else Some((if (certifiedConsensusActive) frozenCommittee else legacyFacilitators) - selfId)

  /** Extract every advertised v35 QC from either pacemaker certificate family.
    *
    * Extraction is intentionally dumb; [[highestVerifiedProposalQc]] is the only production selector. Keeping this traversal here avoids
    * duplicating the nested VCC/TC walk in DAG and Currency.
    */
  def proposalQcCandidates(
    vcc: Option[declaration.ViewChangeCertificate],
    timeoutCertificate: Option[declaration.TimeoutCertificate]
  ): List[CertifiedProposalQC] =
    List.concat(
      vcc.toList.flatMap(_.votes.toNonEmptyList.toList.flatMap(_.value.highestKnownCertifiedQc)),
      timeoutCertificate.toList.flatMap(_.votes.toNonEmptyList.toList.flatMap(_.value.highestKnownCertifiedQc))
    )

  /** Select the uniquely highest certified value from an already-verified collection.
    *
    * Keeping selection separate from verification is useful in tests, but production callers should use [[highestVerifiedProposalQc]]. A
    * syntactically well-formed, higher-view fake must never eclipse a lower, valid certificate.
    */
  private[consensus] def selectHighestProposalQc(
    qcs: Iterable[CertifiedProposalQC]
  ): Either[String, Option[CertifiedProposalQC]] = {
    val candidates = qcs.toList

    candidates.map(_.value.committedView).maximumOption match {
      case None => none[CertifiedProposalQC].asRight[String]
      case Some(maxView) =>
        val atMaxView = candidates.filter(_.value.committedView === maxView)
        Either.cond(
          atMaxView.map(_.valueHash).distinct.size === 1,
          atMaxView.headOption,
          s"divergent_certified_qc_at_view:$maxView"
        )
    }
  }

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

  private def validateCommitteeBindings[F[_]: Applicative: Hasher](
    value: ProposalValue,
    frozenCommittee: Set[PeerId],
    frozenCore: Set[PeerId]
  ): F[Either[String, Unit]] =
    (
      Hasher[F].hash(value.roundStartFacilitators),
      Hasher[F].hash(value.roundStartCore)
    ).mapN { (fullHash, coreHash) =>
      ProposalValue
        .validate(value)
        .productL(
          Either.cond(
            value.roundStartFacilitators.toSortedSet === SortedSet.from(frozenCommittee),
            (),
            "frozen_committee_mismatch"
          )
        )
        .productL(Either.cond(value.roundStartCore.toSortedSet === SortedSet.from(frozenCore), (), "frozen_core_mismatch"))
        .productL(Either.cond(fullHash === value.roundStartFacilitatorsHash, (), "full_committee_hash_mismatch"))
        .productL(Either.cond(coreHash === value.roundStartCoreHash, (), "core_committee_hash_mismatch"))
    }

  def buildProposalQc[F[_]: Async: Hasher: SecurityProvider](
    value: ProposalValue,
    votes: SortedMap[PeerId, OutcomeVote],
    frozenCommittee: Set[PeerId],
    frozenCore: Set[PeerId],
    configuredFraction: Double
  ): F[Either[String, CertifiedProposalQC]] =
    valueHash(value).flatMap { hash =>
      val expected = statement(CertificationPurpose.Prepare, value, hash)
      val required = requiredCoreQuorum(frozenCore.size, configuredFraction)

      validateCommitteeBindings(value, frozenCommittee, frozenCore).flatMap {
        case Left(error) => error.asLeft[CertifiedProposalQC].pure[F]
        case Right(_) =>
          candidateProofs(expected, votes, frozenCore, required) match {
            case Left(error) => error.asLeft[CertifiedProposalQC].pure[F]
            case Right(proofs) =>
              verifyProofs(expected, proofs, frozenCore, required)
                .flatMap(result => result.map(_ => CertifiedProposalQC(value, hash, proofs)).pure[F])
          }
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
        verifyProofs(expected, proofs, frozenCore, required).flatMap { result =>
          result
            .map(_ => CoreCommitQC(proposalQc.valueHash, proposalQc.value.roundStartCoreHash, proofs))
            .pure[F]
        }
    }
  }

  def verifyProposalQc[F[_]: Async: Hasher: SecurityProvider](
    qc: CertifiedProposalQC,
    frozenCommittee: Set[PeerId],
    frozenCore: Set[PeerId],
    configuredFraction: Double
  ): F[Either[String, Unit]] =
    (
      valueHash(qc.value),
      validateCommitteeBindings(qc.value, frozenCommittee, frozenCore)
    ).mapN { (recomputed, bindings) =>
      bindings.productL(Either.cond(recomputed === qc.valueHash, (), "value_hash_mismatch"))
    }.flatMap {
      case Left(error) => error.asLeft[Unit].pure[F]
      case Right(_) =>
        verifyProofs(
          statement(CertificationPurpose.Prepare, qc.value, qc.valueHash),
          qc.signatures,
          frozenCore,
          requiredCoreQuorum(frozenCore.size, configuredFraction)
        )
    }

  /** Verify every advertised QC before choosing the highest valid one.
    *
    * Invalid candidates are ignored, just as invalid pacemaker declarations are not consensus evidence. If two independently valid QCs
    * disagree at the highest view, selection fails closed. This one helper is used by both DAG and Currency leader/follower paths so a
    * buggy or stale peer cannot create layer-specific carry-forward behavior.
    */
  def highestVerifiedProposalQc[F[_]: Async: Hasher: SecurityProvider](
    candidates: Iterable[CertifiedProposalQC],
    frozenCommittee: Set[PeerId],
    frozenCore: Set[PeerId],
    configuredFraction: Double
  ): F[Either[String, Option[CertifiedProposalQC]]] =
    candidates.toList.traverse { qc =>
      verifyProposalQc[F](qc, frozenCommittee, frozenCore, configuredFraction)
        .flatMap(result => result.toOption.as(qc).pure[F])
    }
      .flatMap(valid => selectHighestProposalQc(valid.flatten).pure[F])

  /** Return the first fully verified QC for `value`.
    *
    * Transport layers may learn the same certificate through a proposal, local assembly, or a relayed signature. Keeping candidate
    * filtering and cryptographic verification here prevents DAG and Currency from growing subtly different acceptance rules.
    */
  def firstVerifiedProposalQc[F[_]: Async: Hasher: SecurityProvider](
    value: ProposalValue,
    candidates: Iterable[CertifiedProposalQC],
    frozenCommittee: Set[PeerId],
    frozenCore: Set[PeerId],
    configuredFraction: Double
  ): F[Option[CertifiedProposalQC]] =
    candidates.iterator.filter(_.value === value).toList.findM { qc =>
      verifyProposalQc[F](qc, frozenCommittee, frozenCore, configuredFraction).flatMap(_.isRight.pure[F])
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

  def verifyOutcome[F[_]: Async: Hasher: SecurityProvider](
    outcome: CertifiedOutcome,
    frozenCommittee: Set[PeerId],
    frozenCore: Set[PeerId],
    configuredFraction: Double
  ): F[Either[String, Unit]] =
    verifyProposalQc(outcome.proposalQc, frozenCommittee, frozenCore, configuredFraction).flatMap {
      case Left(error) => error.asLeft[Unit].pure[F]
      case Right(_) =>
        verifyCoreCommitQc(outcome.proposalQc, outcome.coreCommitQc, frozenCore, configuredFraction)
    }

  /** Shared DAG/Currency semantic-value validation. Layer adapters only construct `expected` from their artifact/context types. */
  def validateValue[F[_]: Async: Hasher: SecurityProvider](
    actual: ProposalValue,
    expected: ProposalValue,
    carriedQc: Option[CertifiedProposalQC],
    outerView: Long,
    parentEndTime: Option[Long],
    viewInterval: FiniteDuration,
    maxRoundDuration: Option[FiniteDuration],
    frozenCommittee: Set[PeerId],
    frozenCore: Set[PeerId],
    configuredFraction: Double
  ): F[Either[String, ProposalValue]] =
    carriedQc
      .traverse(verifyProposalQc[F](_, frozenCommittee, frozenCore, configuredFraction))
      .map { carriedResult =>
        for {
          _ <- ProposalValue.validate(actual)
          _ <- ConsensusEndTime.validateProposed(
            actual.consensusEndTime,
            parentEndTime,
            actual.committedView,
            viewInterval,
            maxRoundDuration
          )
          _ <- Either.cond(actual === expected, (), "proposal_value_semantics_mismatch")
          _ <- Either.cond(actual.committedView <= outerView, (), "proposal_value_future_view")
          _ <- carriedResult.sequence_
          _ <- carriedQc.traverse_(qc => Either.cond(qc.value === actual, (), "certified_value_carry_forward_mismatch"))
        } yield actual
      }
}
