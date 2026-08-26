package io.constellationnetwork.node.shared.infrastructure.consensus

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.all._
import cats.{Applicative, Eq, Show}

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.ext.collection.FoldableOps.pickMajority
import io.constellationnetwork.node.shared.infrastructure.consensus.state.QuorumPolicy
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger}
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema.consensus
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.SignatureProof
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import io.circe.Encoder

/** V35 certification primitives for Global L0.
  *
  * Snapshot artifact signatures deliberately keep their existing bare-artifact-hash meaning. These types add a separate Core certificate
  * over every semantic input that can affect the persisted consensus outcome. All signing and verification goes through the repository's
  * standard `Signed` + `Hasher` infrastructure; this module only defines the typed payload and quorum policy.
  */
object CertifiedConsensus {

  val SchemaVersion: Int = consensus.CertifiedConsensusSchema.Version

  type ConsensusDomain = consensus.ConsensusDomain
  val ConsensusDomain: consensus.ConsensusDomain.type = consensus.ConsensusDomain
  type CertificationPurpose = consensus.CertificationPurpose
  val CertificationPurpose: consensus.CertificationPurpose.type = consensus.CertificationPurpose
  type TriggerStatementPurpose = consensus.TriggerStatementPurpose
  val TriggerStatementPurpose: consensus.TriggerStatementPurpose.type = consensus.TriggerStatementPurpose
  type TriggerStatement = consensus.TriggerStatement
  val TriggerStatement: consensus.TriggerStatement.type = consensus.TriggerStatement
  type ProposalValue = consensus.ProposalValue
  val ProposalValue: consensus.ProposalValue.type = consensus.ProposalValue
  type CertifiedRoundAuthorityV1 = consensus.CertifiedRoundAuthorityV1
  val CertifiedRoundAuthorityV1: consensus.CertifiedRoundAuthorityV1.type = consensus.CertifiedRoundAuthorityV1
  type CertificationStatement = consensus.CertificationStatement
  val CertificationStatement: consensus.CertificationStatement.type = consensus.CertificationStatement
  type CertifiedProposalQC = consensus.CertifiedProposalQC
  val CertifiedProposalQC: consensus.CertifiedProposalQC.type = consensus.CertifiedProposalQC
  type CoreCommitQC = consensus.CoreCommitQC
  val CoreCommitQC: consensus.CoreCommitQC.type = consensus.CoreCommitQC
  type CertifiedOutcome = consensus.CertifiedOutcome
  val CertifiedOutcome: consensus.CertifiedOutcome.type = consensus.CertifiedOutcome
  type CertifiedLineageEvidenceV1 = consensus.CertifiedLineageEvidenceV1
  val CertifiedLineageEvidenceV1: consensus.CertifiedLineageEvidenceV1.type = consensus.CertifiedLineageEvidenceV1

  // Resolve the same orphan-instance ambiguity documented for Proposal in declaration.scala.
  implicit val showSortedSelfHealth: Show[SortedMap[PeerId, SelfHealthHint]] =
    Show.show(_.toList.map { case (peerId, hint) => s"${peerId.show}->${hint.show}" }.mkString("{", ",", "}"))

  type OutcomeVote = Signed[CertificationStatement]
  type CoreCommit = Signed[CertificationStatement]

  /** Non-wire identity of the consensus round whose carried QC may influence the current attempt.
    *
    * A QC can remain cryptographically valid long after its round has finished. Pacemaker envelopes therefore must bind nested QCs to the
    * current layer, network, key, and public parent before comparing views; otherwise a genuine high-view QC replayed from an older round
    * can eclipse every current-round certificate and permanently deny liveness.
    */
  final case class CertifiedRoundIdentity(
    domain: ConsensusDomain,
    networkId: String,
    key: Long,
    parentArtifactHash: Hash
  )

  object CertifiedRoundIdentity {
    implicit val eqCertifiedRoundIdentity: Eq[CertifiedRoundIdentity] = Eq.fromUniversalEquals

    def from(value: ProposalValue): CertifiedRoundIdentity =
      CertifiedRoundIdentity(value.domain, value.networkId, value.key, value.parentArtifactHash)
  }

  def valueHash[F[_]: Hasher](value: ProposalValue): F[Hash] =
    Hasher[F].hash(value)

  def roundAuthority[F[_]: Applicative: Hasher](
    facilitators: NonEmptySet[PeerId],
    core: NonEmptySet[PeerId]
  ): F[CertifiedRoundAuthorityV1] =
    (Hasher[F].hash(facilitators), Hasher[F].hash(core)).mapN { (facilitatorsHash, coreHash) =>
      CertifiedRoundAuthorityV1(facilitators, facilitatorsHash, core, coreHash)
    }

  def triggerStatement(
    domain: ConsensusDomain,
    networkId: String,
    key: Long,
    parentArtifactHash: Hash,
    roundStartFacilitatorsHash: Hash,
    consensusConfigHash: Hash,
    trigger: Option[ConsensusTrigger]
  ): TriggerStatement =
    TriggerStatement(
      TriggerStatementPurpose.Facility,
      SchemaVersion,
      domain,
      networkId,
      key,
      parentArtifactHash,
      roundStartFacilitatorsHash,
      consensusConfigHash,
      trigger
    )

  def signTriggerStatement[F[_]: Async: Hasher: SecurityProvider](
    statement: TriggerStatement,
    keyPair: KeyPair
  ): F[Signed[TriggerStatement]] =
    Signed.forAsyncHasher[F, TriggerStatement](statement, keyPair)

  /** Select the transferable trigger evidence an honest leader is allowed to carry.
    *
    * Facility gossip authenticates the outer declaration only at receipt time, so followers cannot verify that envelope later. The inner
    * statement is independently signed and remains transferable. Selection therefore rechecks every inner signature and binding before it
    * can influence the certified trigger.
    *
    * Invalid Facilities are ignored rather than poisoning an otherwise valid phase: a faulty committee member must not gain a permanent
    * veto merely by attaching a malformed statement after enough honest Facilities exist. The remaining evidence must still contain the
    * leader and meet the exact protocol-derived Facility quorum supplied by the layer. If it does not, the leader waits fail-closed for
    * more valid evidence.
    */
  def selectTriggerEvidence[F[_]: Async: Hasher: SecurityProvider](
    facilities: SortedMap[PeerId, declaration.Facility],
    domain: ConsensusDomain,
    networkId: String,
    key: Long,
    parentArtifactHash: Hash,
    roundStartFacilitatorsHash: Hash,
    consensusConfigHash: Hash,
    frozenCommittee: Set[PeerId],
    requiredQuorum: Int,
    requiredLeader: PeerId
  ): F[Either[String, (List[Signed[TriggerStatement]], ConsensusTrigger)]] = {
    val expectedBase = triggerStatement(
      domain,
      networkId,
      key,
      parentArtifactHash,
      roundStartFacilitatorsHash,
      consensusConfigHash,
      none
    )

    facilities.toList.traverse {
      case (peerId, facility) =>
        facility.triggerStatement match {
          case Some(signed)
              if frozenCommittee.contains(peerId) &&
                signed.proofs.size === 1L &&
                signed.proofs.head.id.toPeerId === peerId &&
                signed.value.trigger === facility.trigger &&
                signed.value.copy(trigger = none) === expectedBase =>
            signed.hasValidSignature[F].map(Option.when(_)(signed))
          case _ => none[Signed[TriggerStatement]].pure[F]
        }
    }.map { maybeEvidence =>
      val evidence = maybeEvidence.flatten
      val signers = evidence.map(_.proofs.head.id.toPeerId)
      val selected = pickMajority(evidence.flatMap(_.value.trigger)).getOrElse(EventTrigger)

      for {
        _ <- Either.cond(
          evidence.size >= requiredQuorum,
          (),
          s"trigger_evidence_under_quorum:${evidence.size}/$requiredQuorum"
        )
        _ <- Either.cond(signers.contains(requiredLeader), (), "trigger_evidence_missing_leader")
      } yield evidence -> selected
    }
  }

  /** Verify a leader-carried Facility trigger-evidence set and return its one authorized trigger.
    *
    * The caller supplies the protocol-derived Facility phase threshold. The function never reads local event pacing, local Facility arrival
    * state, or an incidental declaration cache. Evidence order is irrelevant; signer identity comes from each inner signature. An all-None
    * carried set deterministically selects EventTrigger, exactly matching the production Facility-phase default. At/after v35 activation
    * callers fail closed on a missing Facility statement before this verifier can authorize a fresh proposal.
    */
  def validateTriggerEvidence[F[_]: Async: Hasher: SecurityProvider](
    evidence: List[Signed[TriggerStatement]],
    domain: ConsensusDomain,
    networkId: String,
    key: Long,
    parentArtifactHash: Hash,
    roundStartFacilitatorsHash: Hash,
    consensusConfigHash: Hash,
    frozenCommittee: Set[PeerId],
    requiredQuorum: Int,
    proposedTrigger: ConsensusTrigger,
    requiredLeader: PeerId
  ): F[Either[String, ConsensusTrigger]] = {
    val entries = evidence.map(signed => signed.proofs.head.id.toPeerId -> signed)
    val signers = entries.map(_._1)
    val expectedBase = triggerStatement(
      domain,
      networkId,
      key,
      parentArtifactHash,
      roundStartFacilitatorsHash,
      consensusConfigHash,
      none
    )
    val structure = for {
      _ <- Either.cond(evidence.nonEmpty, (), "trigger_evidence_empty")
      _ <- Either.cond(evidence.forall(_.proofs.size === 1L), (), "trigger_evidence_requires_single_signer")
      _ <- Either.cond(signers.distinct.size === signers.size, (), "trigger_evidence_duplicate_signer")
      _ <- Either.cond(signers.toSet.subsetOf(frozenCommittee), (), "trigger_evidence_signer_outside_committee")
      _ <- Either.cond(signers.contains(requiredLeader), (), "trigger_evidence_missing_leader")
      _ <- Either.cond(signers.size >= requiredQuorum, (), s"trigger_evidence_under_quorum:${signers.size}/$requiredQuorum")
      _ <- Either.cond(
        evidence.forall { signed =>
          val value = signed.value
          value.copy(trigger = none) === expectedBase
        },
        (),
        "trigger_evidence_binding_mismatch"
      )
      selected = pickMajority(evidence.flatMap(_.value.trigger)).getOrElse(EventTrigger)
      _ <- Either.cond(selected === proposedTrigger, (), "trigger_evidence_majority_mismatch")
    } yield selected

    structure match {
      case Left(error) => error.asLeft[ConsensusTrigger].pure[F]
      case Right(selected) =>
        evidence
          .traverse(_.hasValidSignature[F])
          .map(valid => Either.cond(valid.forall(identity), selected, "trigger_evidence_invalid_signature"))
    }
  }

  /** Construct the canonical Global L0 semantic value. The domain remains explicit in the signed statement so a future version can add a
    * new domain without silently reinterpreting v35 bytes.
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
    nextRoundAuthority: CertifiedRoundAuthorityV1,
    nextOperationalStateHash: Hash,
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
        nextRoundAuthority = nextRoundAuthority,
        nextOperationalStateHash = nextOperationalStateHash,
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
      Hasher[F].hash(roundStartCore),
      Hasher[F].hash(certified.nextRoundAuthority.facilitators),
      Hasher[F].hash(certified.nextRoundAuthority.core)
    ).mapN { (contextHash, fullHash, coreHash, nextFullHash, nextCoreHash) =>
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
        roundStartCoreHash = coreHash,
        nextRoundAuthority = certified.nextRoundAuthority.copy(
          facilitatorsHash = nextFullHash,
          coreHash = nextCoreHash
        )
      )
    }

  def requiredCoreQuorum(coreSize: Int, configuredFraction: Double): Int =
    math.max(QuorumPolicy.supermajority(coreSize), QuorumPolicy.fromFraction(coreSize, configuredFraction))

  /** V35 artifact finality remains a separate full-committee rule. The Core term preserves configurations whose liveness threshold is
    * stricter than the broad committee fraction; the full term prevents a Core minority from finalizing for Core + Tier-1.
    */
  def requiredArtifactQuorum(fullSize: Int, coreSize: Int, configuredFraction: Double): Int =
    math.max(
      math.max(1, QuorumPolicy.fromFraction(fullSize, configuredFraction)),
      math.max(1, QuorumPolicy.fromFraction(coreSize, configuredFraction))
    )

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
    * duplicating the nested VCC/TC walk across Global L0 call sites.
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

  private def candidateProofs[F[_]: Async: Hasher: SecurityProvider](
    expected: CertificationStatement,
    votes: SortedMap[PeerId, Signed[CertificationStatement]],
    frozenCore: Set[PeerId],
    requiredQuorum: Int
  ): F[Either[String, NonEmptySet[SignatureProof]]] =
    votes.toList.traverse {
      case (peerId, signed)
          if frozenCore.contains(peerId) &&
            signed.value === expected &&
            signed.proofs.size === 1L &&
            signed.proofs.head.id.toPeerId === peerId =>
        // Storage is first-write-wins per origin. Verify each candidate independently so one
        // malformed first arrival is ignored instead of poisoning an otherwise honest quorum.
        signed.hasValidSignature[F].map(Option.when(_)(signed.proofs.head))
      case _ => none[SignatureProof].pure[F]
    }
      .map(_.flatten)
      .map { proofs =>
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
      Hasher[F].hash(value.roundStartCore),
      Hasher[F].hash(value.nextRoundAuthority.facilitators),
      Hasher[F].hash(value.nextRoundAuthority.core)
    ).mapN { (fullHash, coreHash, nextFullHash, nextCoreHash) =>
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
        .productL(
          Either.cond(
            nextFullHash === value.nextRoundAuthority.facilitatorsHash,
            (),
            "next_full_committee_hash_mismatch"
          )
        )
        .productL(Either.cond(nextCoreHash === value.nextRoundAuthority.coreHash, (), "next_core_committee_hash_mismatch"))
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
          candidateProofs[F](expected, votes, frozenCore, required).flatMap {
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

    candidateProofs[F](expected, commits, frozenCore, required).flatMap {
      case Left(error) => error.asLeft[CoreCommitQC].pure[F]
      case Right(proofs) =>
        verifyProofs(expected, proofs, frozenCore, required).flatMap { result =>
          result
            .map(_ => CoreCommitQC(proposalQc.valueHash, proposalQc.value.roundStartCoreHash, proofs))
            .pure[F]
        }
    }
  }

  private def verifyProposalQcWithRequiredQuorum[F[_]: Async: Hasher: SecurityProvider](
    qc: CertifiedProposalQC,
    frozenCommittee: Set[PeerId],
    frozenCore: Set[PeerId],
    requiredQuorum: Int
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
          requiredQuorum
        )
    }

  def verifyProposalQc[F[_]: Async: Hasher: SecurityProvider](
    qc: CertifiedProposalQC,
    frozenCommittee: Set[PeerId],
    frozenCore: Set[PeerId],
    configuredFraction: Double
  ): F[Either[String, Unit]] =
    verifyProposalQcWithRequiredQuorum(
      qc,
      frozenCommittee,
      frozenCore,
      requiredCoreQuorum(frozenCore.size, configuredFraction)
    )

  /** Re-verify a QC restored from the node-local safety journal before it is honored as carry-forward evidence.
    *
    * The journal parser deliberately hydrates the complete lock conservatively so a restart cannot forget a prior vote. Cryptographic
    * authority still comes only from this ordinary QC verifier once the round's frozen committee and configured quorum are known.
    */
  def verifyPersistedLockedQc[F[_]: Async: Hasher: SecurityProvider](
    lock: Option[CertifiedVoteLock],
    frozenCommittee: Set[PeerId],
    frozenCore: Set[PeerId],
    configuredFraction: Double
  ): F[Either[String, Option[CertifiedProposalQC]]] =
    lock.flatMap(_.lockedQc).fold(none[CertifiedProposalQC].asRight[String].pure[F]) { qc =>
      verifyProposalQc[F](qc, frozenCommittee, frozenCore, configuredFraction).flatMap(_.as(qc.some).pure[F])
    }

  /** Verify every advertised QC before choosing the highest valid one.
    *
    * Candidates outside `expectedRound` are ignored before view comparison. Remaining invalid candidates are ignored, just as invalid
    * pacemaker declarations are not consensus evidence. If two independently valid QCs disagree at the highest view, selection fails
    * closed. Using one helper for Global L0 leader and follower paths prevents a buggy or stale peer from creating path-specific
    * carry-forward behavior.
    */
  def highestVerifiedProposalQc[F[_]: Async: Hasher: SecurityProvider](
    candidates: Iterable[CertifiedProposalQC],
    expectedRound: CertifiedRoundIdentity,
    frozenCommittee: Set[PeerId],
    frozenCore: Set[PeerId],
    configuredFraction: Double
  ): F[Either[String, Option[CertifiedProposalQC]]] =
    candidates.toList
      .filter(qc => CertifiedRoundIdentity.from(qc.value) === expectedRound)
      .traverse { qc =>
        verifyProposalQc[F](qc, frozenCommittee, frozenCore, configuredFraction)
          .flatMap(result => result.toOption.as(qc).pure[F])
      }
      .flatMap(valid => selectHighestProposalQc(valid.flatten).pure[F])

  /** Return the first fully verified QC for `value`.
    *
    * Transport layers may learn the same certificate through a proposal, local assembly, or a relayed signature. Keeping candidate
    * filtering and cryptographic verification here prevents Global L0 call sites from growing subtly different acceptance rules.
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

  private def verifyCoreCommitQcWithRequiredQuorum[F[_]: Async: Hasher: SecurityProvider](
    proposalQc: CertifiedProposalQC,
    commitQc: CoreCommitQC,
    frozenCore: Set[PeerId],
    requiredQuorum: Int
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
          requiredQuorum
        )
    }
  }

  def verifyCoreCommitQc[F[_]: Async: Hasher: SecurityProvider](
    proposalQc: CertifiedProposalQC,
    commitQc: CoreCommitQC,
    frozenCore: Set[PeerId],
    configuredFraction: Double
  ): F[Either[String, Unit]] =
    verifyCoreCommitQcWithRequiredQuorum(
      proposalQc,
      commitQc,
      frozenCore,
      requiredCoreQuorum(frozenCore.size, configuredFraction)
    )

  private def verifyOutcomeWithRequiredQuorum[F[_]: Async: Hasher: SecurityProvider](
    outcome: CertifiedOutcome,
    frozenCommittee: Set[PeerId],
    frozenCore: Set[PeerId],
    requiredQuorum: Int
  ): F[Either[String, Unit]] =
    verifyProposalQcWithRequiredQuorum(outcome.proposalQc, frozenCommittee, frozenCore, requiredQuorum).flatMap {
      case Left(error) => error.asLeft[Unit].pure[F]
      case Right(_) =>
        verifyCoreCommitQcWithRequiredQuorum(outcome.proposalQc, outcome.coreCommitQc, frozenCore, requiredQuorum)
    }

  def verifyOutcome[F[_]: Async: Hasher: SecurityProvider](
    outcome: CertifiedOutcome,
    frozenCommittee: Set[PeerId],
    frozenCore: Set[PeerId],
    configuredFraction: Double
  ): F[Either[String, Unit]] =
    verifyOutcomeWithRequiredQuorum(
      outcome,
      frozenCommittee,
      frozenCore,
      requiredCoreQuorum(frozenCore.size, configuredFraction)
    )

  /** Historical verification uses the protocol-fixed quorum intersection floor, not the downloader's current configured liveness policy.
    * This deliberately bypasses `fromFraction`: historical safety is a named protocol rule, not a synthetic configuration value.
    */
  def verifyOutcomeAtSafetyFloor[F[_]: Async: Hasher: SecurityProvider](
    outcome: CertifiedOutcome,
    frozenCommittee: Set[PeerId],
    frozenCore: Set[PeerId]
  ): F[Either[String, Unit]] =
    verifyOutcomeWithRequiredQuorum(
      outcome,
      frozenCommittee,
      frozenCore,
      QuorumPolicy.supermajority(frozenCore.size)
    )

  /** Validate the exact child-carried certificate envelope against this node's already-trusted parent outcome.
    *
    * The semantic value must be identical, but the valid prepare/commit proof subset may differ. The returned object is therefore always
    * the leader-carried envelope, never a locally reconstructed substitute. Artifact reconstruction must embed this returned value exactly
    * (wiring invariant W1), otherwise honest followers could produce different child bytes from equivalent certificates.
    *
    * `None -> None` is the only root/activation case. Once a trusted parent has a certificate, omission fails closed; carrying a
    * certificate before the trusted parent does is equally invalid. This makes the exception local-authority-derived rather than
    * peer-asserted.
    */
  def verifyCarriedParentOutcome[F[_]: Async: Hasher: SecurityProvider](
    carried: Option[CertifiedLineageEvidenceV1],
    trustedParent: Option[CertifiedOutcome],
    domain: ConsensusDomain,
    configuredFraction: Double
  ): F[Either[String, Option[CertifiedLineageEvidenceV1]]] =
    (trustedParent, carried) match {
      case (None, None)    => none[CertifiedLineageEvidenceV1].asRight[String].pure[F]
      case (None, Some(_)) => "certified_lineage_unexpected_at_root".asLeft[Option[CertifiedLineageEvidenceV1]].pure[F]
      case (Some(_), None) => "certified_lineage_missing_after_root".asLeft[Option[CertifiedLineageEvidenceV1]].pure[F]
      case (Some(expected), Some(actual)) =>
        val expectedValue = expected.proposalQc.value
        val actualValue = actual.parentOutcome.proposalQc.value
        val frozenCommittee = expectedValue.roundStartFacilitators.toSortedSet.toSet
        val frozenCore = expectedValue.roundStartCore.toSortedSet.toSet
        val structure = for {
          _ <- Either.cond(actualValue.domain === domain, (), "certified_lineage_domain_mismatch")
          _ <- Either.cond(actualValue === expectedValue, (), "certified_lineage_parent_value_mismatch")
          _ <- Either.cond(
            actual.parentOutcome.proposalQc.valueHash === expected.proposalQc.valueHash,
            (),
            "certified_lineage_parent_value_hash_mismatch"
          )
        } yield ()

        structure match {
          case Left(error) => error.asLeft[Option[CertifiedLineageEvidenceV1]].pure[F]
          case Right(_) =>
            verifyOutcome(actual.parentOutcome, frozenCommittee, frozenCore, configuredFraction)
              .flatMap(_.as(actual.some).pure[F])
        }
    }

  /** Historical counterpart of [[verifyCarriedParentOutcome]]: certificate continuity is checked at the protocol-fixed safety floor and is
    * therefore independent of the downloader's current liveness configuration.
    */
  def verifyHistoricalCarriedParentOutcome[F[_]: Async: Hasher: SecurityProvider](
    carried: Option[CertifiedLineageEvidenceV1],
    trustedParent: Option[CertifiedOutcome],
    domain: ConsensusDomain
  ): F[Either[String, Option[CertifiedLineageEvidenceV1]]] =
    (trustedParent, carried) match {
      case (None, None)    => none[CertifiedLineageEvidenceV1].asRight[String].pure[F]
      case (None, Some(_)) => "certified_lineage_unexpected_at_root".asLeft[Option[CertifiedLineageEvidenceV1]].pure[F]
      case (Some(_), None) => "certified_lineage_missing_after_root".asLeft[Option[CertifiedLineageEvidenceV1]].pure[F]
      case (Some(expected), Some(actual)) =>
        val expectedValue = expected.proposalQc.value
        val actualValue = actual.parentOutcome.proposalQc.value
        val frozenCommittee = expectedValue.roundStartFacilitators.toSortedSet.toSet
        val frozenCore = expectedValue.roundStartCore.toSortedSet.toSet
        val structure = for {
          _ <- Either.cond(actualValue.domain === domain, (), "certified_lineage_domain_mismatch")
          _ <- Either.cond(actualValue === expectedValue, (), "certified_lineage_parent_value_mismatch")
          _ <- Either.cond(
            actual.parentOutcome.proposalQc.valueHash === expected.proposalQc.valueHash,
            (),
            "certified_lineage_parent_value_hash_mismatch"
          )
        } yield ()

        structure match {
          case Left(error) => error.asLeft[Option[CertifiedLineageEvidenceV1]].pure[F]
          case Right(_) =>
            verifyOutcomeAtSafetyFloor(actual.parentOutcome, frozenCommittee, frozenCore)
              .flatMap(_.as(actual.some).pure[F])
        }
    }

  /** Verify and replay a public child-carried certificate chain from an independently trusted root.
    *
    * Certificate placement is intentionally shifted by one round: public frame `N + 1` carries the transferable evidence for frame `N`. The
    * terminal frame has no child yet, so its evidence comes from the authenticated peer outcome endpoint. The generic fold owns only this
    * ordering/continuity rule and the shared certificate checks; the Global L0 adapter remains responsible for re-deriving its public
    * artifact, context, and membership transition.
    *
    * No partially replayed state is returned on failure. Callers may persist the returned states only after the complete fold succeeds;
    * this keeps a malformed interior child from installing a prefix as local authority.
    */
  def verifySequentialLineage[F[_]: Async: Hasher: SecurityProvider, State, Frame](
    trustedRoot: State,
    trustedRootKey: Long,
    frames: List[Frame],
    terminalEvidence: Option[CertifiedLineageEvidenceV1],
    domain: ConsensusDomain,
    configuredFraction: Double,
    keyOf: Frame => Long,
    lineageOf: Frame => Option[CertifiedLineageEvidenceV1],
    certifiedOutcomeOf: State => Option[CertifiedOutcome]
  )(
    advance: (State, Frame, CertifiedLineageEvidenceV1) => F[Either[String, State]]
  ): F[Either[String, List[State]]] = {
    def expectedSuccessor(key: Long): Either[String, Long] = {
      val next = BigInt(key) + 1
      Either.cond(next <= BigInt(Long.MaxValue), next.toLong, "certified_lineage_key_overflow")
    }

    def loop(
      trusted: State,
      trustedKey: Long,
      remaining: List[Frame],
      accepted: List[State]
    ): F[Either[String, List[State]]] =
      remaining match {
        case Nil => accepted.reverse.asRight[String].pure[F]
        case current :: tail =>
          val currentKey = keyOf(current)
          val structure = for {
            expected <- expectedSuccessor(trustedKey)
            _ <- Either.cond(currentKey === expected, (), s"certified_lineage_non_contiguous:$trustedKey:$currentKey")
            authority <- tail match {
              case next :: _ =>
                lineageOf(next).toRight(s"certified_lineage_missing_child_certificate:$currentKey")
              case Nil => terminalEvidence.toRight(s"certified_lineage_terminal_certificate_missing:$currentKey")
            }
            value = authority.parentOutcome.proposalQc.value
            _ <- Either.cond(value.domain === domain, (), s"certified_lineage_authority_domain_mismatch:$currentKey")
            _ <- Either.cond(value.key === currentKey, (), s"certified_lineage_authority_key_mismatch:$currentKey:${value.key}")
          } yield authority

          structure match {
            case Left(error) => error.asLeft[List[State]].pure[F]
            case Right(authority) =>
              verifyCarriedParentOutcome[F](
                lineageOf(current),
                certifiedOutcomeOf(trusted),
                domain,
                configuredFraction
              ).flatMap {
                case Left(error) => s"certified_lineage_parent_invalid:$currentKey:$error".asLeft[List[State]].pure[F]
                case Right(_) =>
                  advance(trusted, current, authority).flatMap {
                    case Left(error) => s"certified_lineage_round_invalid:$currentKey:$error".asLeft[List[State]].pure[F]
                    case Right(next) => loop(next, currentKey, tail, next :: accepted)
                  }
              }
          }
      }

    frames match {
      case Nil =>
        Either
          .cond(terminalEvidence.isEmpty, List.empty[State], "certified_lineage_terminal_without_frames")
          .pure[F]
      case _ => loop(trustedRoot, trustedRootKey, frames, List.empty)
    }
  }

  /** Verify a certified value against the exact round identity reconstructed from a locally known parent. This is Global L0's same-key
    * recovery trust boundary: the adapter provides typed context and frozen sets while this helper reuses the ordinary value/QC validation
    * path.
    */
  def verifyBoundOutcome[F[_]: Async: Hasher: SecurityProvider, Context: Encoder](
    outcome: CertifiedOutcome,
    domain: ConsensusDomain,
    networkId: String,
    key: Long,
    parentArtifactHash: Hash,
    artifactHash: Hash,
    context: Context,
    roundStartFacilitators: NonEmptySet[PeerId],
    roundStartCore: NonEmptySet[PeerId],
    configuredFraction: Double,
    parentEndTime: Option[Long],
    viewInterval: FiniteDuration,
    maxRoundDuration: Option[FiniteDuration]
  ): F[Either[String, Unit]] = {
    val value = outcome.proposalQc.value
    val fullSet = roundStartFacilitators.toSortedSet.toSet
    val coreSet = roundStartCore.toSortedSet.toSet

    for {
      expected <- rederiveCertifiedValue[F, Context](
        value,
        domain,
        networkId,
        key,
        parentArtifactHash,
        artifactHash,
        context,
        roundStartFacilitators,
        roundStartCore
      )
      valueValidation <- validateValue[F](
        value,
        expected,
        carriedQc = None,
        outerView = value.committedView,
        parentEndTime = parentEndTime,
        viewInterval = viewInterval,
        maxRoundDuration = maxRoundDuration,
        frozenCommittee = fullSet,
        frozenCore = coreSet,
        configuredFraction = configuredFraction
      )
      qcValidation <- verifyOutcome[F](outcome, fullSet, coreSet, configuredFraction)
    } yield valueValidation.void.productR(qcValidation)
  }

  /** Authenticate an already-completed historical round from its certified identity fields.
    *
    * Interior lineage verification deliberately does not require the historical context preimage: the live Core quorum already certified
    * `contextHash`, and historical authority consumes only the QC-certified committee transition. The independently trusted root and the
    * downloaded terminal frame still validate their complete artifact/context state proofs. Supplying `expectedContextHash` tightens the
    * same verifier at those boundaries without introducing a second certificate rule.
    */
  def verifyHistoricalOutcomeIdentity[F[_]: Async: Hasher: SecurityProvider](
    outcome: CertifiedOutcome,
    domain: ConsensusDomain,
    networkId: String,
    key: Long,
    parentArtifactHash: Hash,
    artifactHash: Hash,
    expectedContextHash: Option[Hash],
    roundStartFacilitators: NonEmptySet[PeerId],
    roundStartCore: NonEmptySet[PeerId]
  ): F[Either[String, Unit]] = {
    val value = outcome.proposalQc.value
    val fullSet = roundStartFacilitators.toSortedSet.toSet
    val coreSet = roundStartCore.toSortedSet.toSet

    verifyOutcomeAtSafetyFloor[F](outcome, fullSet, coreSet).flatMap { qcValidation =>
      ProposalValue
        .validate(value)
        .productL(Either.cond(value.domain === domain, (), "historical_domain_mismatch"))
        .productL(Either.cond(value.networkId === networkId, (), "historical_network_mismatch"))
        .productL(Either.cond(value.key === key, (), "historical_key_mismatch"))
        .productL(Either.cond(value.parentArtifactHash === parentArtifactHash, (), "historical_parent_hash_mismatch"))
        .productL(Either.cond(value.artifactHash === artifactHash, (), "historical_artifact_hash_mismatch"))
        .productL(
          Either.cond(
            expectedContextHash.forall(_ === value.contextHash),
            (),
            "historical_context_hash_mismatch"
          )
        )
        .productL(
          Either.cond(
            value.roundStartFacilitators === roundStartFacilitators,
            (),
            "historical_full_committee_mismatch"
          )
        )
        .productL(Either.cond(value.roundStartCore === roundStartCore, (), "historical_core_committee_mismatch"))
        .productR(qcValidation)
        .pure[F]
    }
  }

  /** Boundary convenience wrapper that also authenticates the supplied context preimage. */
  def verifyHistoricalBoundOutcome[F[_]: Async: Hasher: SecurityProvider, Context: Encoder](
    outcome: CertifiedOutcome,
    domain: ConsensusDomain,
    networkId: String,
    key: Long,
    parentArtifactHash: Hash,
    artifactHash: Hash,
    context: Context,
    roundStartFacilitators: NonEmptySet[PeerId],
    roundStartCore: NonEmptySet[PeerId]
  ): F[Either[String, Unit]] =
    Hasher[F].hash(context).flatMap { contextHash =>
      verifyHistoricalOutcomeIdentity(
        outcome,
        domain,
        networkId,
        key,
        parentArtifactHash,
        artifactHash,
        contextHash.some,
        roundStartFacilitators,
        roundStartCore
      )
    }

  /** Verify legacy artifact proofs against the frozen v35 committee without changing their historical bare-artifact-hash meaning.
    */
  def verifyArtifactProofs[F[_]: Async: Hasher: SecurityProvider, Artifact: Encoder](
    signedArtifact: Signed[Artifact],
    frozenCommittee: Set[PeerId],
    requiredQuorum: Int
  ): F[Either[String, Unit]] = {
    val signers = signedArtifact.proofs.toSortedSet.toList.map(_.id.toPeerId)
    val structure = for {
      _ <- Either.cond(signers.distinct.size === signers.size, (), "duplicate_artifact_signer")
      _ <- Either.cond(signers.toSet.subsetOf(frozenCommittee), (), "artifact_signer_outside_frozen_committee")
      _ <- Either.cond(signers.size >= requiredQuorum, (), s"artifact_under_quorum:${signers.size}/$requiredQuorum")
    } yield ()

    structure match {
      case Left(error) => error.asLeft[Unit].pure[F]
      case Right(_) =>
        signedArtifact.hasValidSignature[F].map(Either.cond(_, (), "invalid_artifact_signature"))
    }
  }

  /** Global-L0 semantic-value validation. Layer adapters construct `expected` from the signed Global artifact and context.
    */
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
