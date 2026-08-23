package io.constellationnetwork.currency.l0.snapshot

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.Duration

import io.constellationnetwork.currency.l0.snapshot.schema._
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.OrdinalJsonSidecarStorage
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{CurrencyStateProofSelector, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{HasherSelector, SecurityProvider}

/** Fail-closed trust boundary for a peer-supplied v35 Currency outcome.
  *
  * The fast path binds an exact locally retained predecessor sidecar to its public artifact/context. If that cache is absent, the validator
  * replays the public child-carried certificate chain from the configured activation parent (or canonical genesis root) through the peer's
  * terminal outcome. The complete fold must succeed before the candidate can initialize consensus; no interior private outcome is written.
  *
  * Currency binary continuity uses the bounded proof envelope carried by each child. At the first certified round, the first binary's
  * complete frozen-committee signatures establish the otherwise-unavailable legacy/genesis binary parent hash. That scalar grants no
  * committee authority: the round committee is independently derived from the signed root artifact/controller evidence, and the binary
  * proofs are verified only after that derivation. Every later link is checked against the prior reconstructed binary hash.
  */
object CurrencyCertifiedDownloadValidator {

  private sealed trait TrustedParentKind
  private object TrustedParentKind {
    case object Certified extends TrustedParentKind
    case object AuthorizedRoot extends TrustedParentKind
  }

  private final case class TrustedParent(outcome: CurrencyConsensusOutcome, kind: TrustedParentKind)

  private final case class RoundProjection(
    selected: List[PeerId],
    committee: CertifiedRoundCommitteeProjector.Projection
  )

  private final case class PublicRound(
    key: CurrencySnapshotKey,
    artifact: Signed[CurrencyIncrementalSnapshot],
    context: CurrencySnapshotContext
  )

  private def carried(outcome: CurrencyConsensusOutcome): CertifiedRoundCommitteeProjector.CarriedControllerState =
    CertifiedRoundCommitteeProjector.CarriedControllerState(
      activeScores = outcome.activeAdmissionScores.toMap,
      peerQuality = outcome.peerQuality.toMap,
      peerTiers = outcome.peerTiers,
      viewChanges = outcome.peerViewChanges.toMap,
      selfHealth = outcome.peerSelfHealth.toMap
    )

  private def publicGenesisRoot(
    snapshot: Signed[CurrencyIncrementalSnapshot],
    context: CurrencySnapshotContext,
    snapshotHash: Hash
  ): CurrencyConsensusOutcome = {
    val proofSigners = snapshot.proofs.toSortedSet.toList.map(_.id.toPeerId)

    CurrencyConsensusOutcome(
      snapshot.ordinal,
      Facilitators(proofSigners),
      RemovedFacilitators.empty,
      WithdrawnFacilitators.empty,
      EligibleFacilitators(proofSigners),
      Finished(
        snapshot,
        // The public Currency artifact does not carry its legacy binary. The first
        // certified successor supplies this hash through a complete-committee-signed
        // binary link before the value can be used.
        Hash.empty,
        context,
        EventTrigger,
        Candidates.empty,
        Hash.empty,
        snapshotHash
      ),
      recentProofSizes = SortedMap(snapshot.ordinal -> snapshot.proofs.size.toInt),
      expandedBeyondSingleton = Some(proofSigners.size > 1)
    )
  }

  private def trustedParentKind(
    outcome: CurrencyConsensusOutcome,
    activationKey: Long
  ): Either[String, TrustedParentKind] =
    if (outcome.finished.certifiedOutcome.nonEmpty && outcome.finished.certifiedBinary.nonEmpty)
      TrustedParentKind.Certified.asRight
    else if (
      CertifiedConsensusGenesis.isRootKey(activationKey, outcome.key) ||
      (activationKey > CertifiedConsensusGenesis.FirstIncrementalOrdinal.value.value &&
        outcome.key.value.value == activationKey - 1L)
    )
      TrustedParentKind.AuthorizedRoot.asRight
    else
      "trusted_predecessor_not_certified_or_authorized_root".asLeft

  def make[F[_]: Async: Parallel: JsonSerializer: HasherSelector: SecurityProvider](
    config: ConsensusConfig,
    coreCommitteeSize: Int,
    seedlistPeerIds: Set[PeerId],
    getCurrencyAddress: F[Address],
    facilitatorSelector: FacilitatorSelector,
    isContextEligible: (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotContext, PeerId) => F[Boolean],
    getSnapshot: SnapshotOrdinal => F[Option[Signed[CurrencyIncrementalSnapshot]]],
    getSnapshotInfo: SnapshotOrdinal => F[Option[CurrencySnapshotInfo]],
    certifiedOutcomeSidecar: OrdinalJsonSidecarStorage[F, CurrencyConsensusOutcome],
    stateAdvancer: CurrencySnapshotConsensusStateAdvancer[F]
  )(implicit currencyStateProofSelector: CurrencyStateProofSelector): CurrencyConsensusOutcome => F[Unit] = {

    def projectAuthorizedRoot(
      key: CurrencySnapshotKey,
      trustedParent: CurrencyConsensusOutcome
    ): F[Either[String, RoundProjection]] = {
      val seedlistEligible = trustedParent.facilitators.value.filter(pid => seedlistPeerIds.isEmpty || seedlistPeerIds.contains(pid))

      seedlistEligible
        .filterA(
          isContextEligible(
            trustedParent.finished.signedMajorityArtifact,
            trustedParent.finished.context,
            _
          )
        )
        .map { eligible =>
          for {
            _ <- Either.cond(eligible.nonEmpty, (), "trusted_root_eligible_committee_empty")
            selected = facilitatorSelector.select(eligible, trustedParent.finished.snapshotHash)
            committee = CertifiedRoundCommitteeProjector.project(
              key = key,
              selectedFacilitators = selected,
              recentSigners = trustedParent.recentSigners,
              controllerEvidence = trustedParent.controllerEvidence.getOrElse(SortedMap.empty),
              carried = carried(trustedParent),
              config = config,
              coreCommitteeSize = coreCommitteeSize,
              forcedTier1Peers = Set.empty
            )
            _ <- Either.cond(committee.signingFacilitators.nonEmpty, (), "trusted_root_signing_committee_empty")
          } yield RoundProjection(selected, committee)
        }
    }

    def projectRound(
      key: CurrencySnapshotKey,
      trustedParent: TrustedParent
    ): F[Either[String, RoundProjection]] =
      trustedParent.kind match {
        case TrustedParentKind.Certified =>
          trustedParent.outcome.finished.certifiedOutcome match {
            case Some(certified) =>
              CertifiedRoundCommitteeProjector
                .fromCertifiedCurrencyParent[F](
                  key = key,
                  parentValue = certified.proposalQc.value,
                  parentRecentSigners = trustedParent.outcome.recentSigners,
                  parentControllerEvidence = trustedParent.outcome.controllerEvidence.getOrElse(SortedMap.empty),
                  parentCarried = carried(trustedParent.outcome),
                  config = config,
                  coreCommitteeSize = coreCommitteeSize,
                  seedlistPeerIds = seedlistPeerIds,
                  isContextEligible = isContextEligible(
                    trustedParent.outcome.finished.signedMajorityArtifact,
                    trustedParent.outcome.finished.context,
                    _
                  ),
                  facilitatorSelector = facilitatorSelector,
                  parentArtifactHash = trustedParent.outcome.finished.snapshotHash
                )
                .flatMap(result => result.map(p => RoundProjection(p.nextRound.selectedCommittee, p.committee)).pure[F])
            case None => "trusted_predecessor_certificate_missing".asLeft[RoundProjection].pure[F]
          }

        case TrustedParentKind.AuthorizedRoot => projectAuthorizedRoot(key, trustedParent.outcome)
      }

    def stateFromTrustedParent(
      key: CurrencySnapshotKey,
      trustedParent: TrustedParent
    ): F[Either[String, CurrencySnapshotConsensusState]] =
      projectRound(key, trustedParent).map(
        _.flatMap { projected =>
          val full = projected.committee.signingFacilitators
          val core = projected.committee.committees.core
          val tier1 = projected.committee.committees.tier1
          val controllerInputs = projected.committee.controllerInputs
          val leaderEligibility = LeaderEligibility.fromRecentSigners(
            core = core,
            peerQuality = controllerInputs.peerQuality,
            recentSigners = trustedParent.outcome.recentSigners,
            minParticipationObservations = config.minParticipationObservations,
            minLeaderPoolSize = config.minLeaderPoolSize
          )

          leaderEligibility.leaderPool.headOption
            .toRight("downloaded_round_leader_pool_empty")
            .map { _ =>
              val leader = facilitatorSelector.selectLeaderWeighted(
                leaderEligibility.leaderPool,
                trustedParent.outcome.finished.snapshotHash,
                viewNumber = 0,
                qualityScores = controllerInputs.peerQuality,
                selfHealthHints = controllerInputs.selfHealth,
                peerViewChanges = controllerInputs.viewChanges,
                minLeaderRatioPct = config.leaderRotationMinRatioPct,
                hardLeaderQualityScorePct = config.hardLeaderQualityScorePct,
                minLeaderPoolSize = config.minLeaderPoolSize
              )

              ConsensusState[CurrencySnapshotKey, CurrencySnapshotStatus, CurrencyConsensusOutcome, CurrencyConsensusKind](
                key = key,
                lastOutcome = trustedParent.outcome,
                facilitators = Facilitators(full),
                roundStartFacilitators = Facilitators(full),
                status = CollectingFacilities(
                  maybeTrigger = None,
                  facilitatorsHash = trustedParent.outcome.finished.facilitatorsHash,
                  lastSnapshotHash = trustedParent.outcome.finished.snapshotHash
                ),
                createdAt = Duration.Zero,
                eligibleFacilitators = EligibleFacilitators(projected.selected),
                coreFacilitators = CoreFacilitators(core),
                tier1Facilitators = Tier1Facilitators(tier1),
                leader = leader,
                initialViewNumber = 0,
                viewNumber = 0,
                entropy = trustedParent.outcome.finished.snapshotHash,
                certifiedConsensusActive = true
              )
            }
        }
      )

    def locallyValidatedSnapshot(
      ordinal: SnapshotOrdinal
    ): F[Either[String, (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotContext)]] = {
      val proofOrdinal =
        if (ordinal === SnapshotOrdinal.MinIncrementalValue) SnapshotOrdinal.MinValue else ordinal

      HasherSelector[F].forOrdinal(ordinal) { implicit hasher =>
        (getSnapshot(ordinal), getSnapshotInfo(ordinal)).tupled.flatMap {
          case (Some(snapshot), Some(info)) =>
            val signerIds = snapshot.proofs.toSortedSet.toList.map(_.id.toPeerId)
            val signerSet = signerIds.toSet

            for {
              signatureValid <- snapshot.hasValidSignature[F]
              contextProof <- info.stateProof[F](proofOrdinal)
              // IdentifierStorage is initialized by the selected startup program after
              // Services are allocated. Resolve it only on this active validation path;
              // constructing consensus (and all pre-activation validation) must not read it.
              currencyAddress <- getCurrencyAddress
            } yield {
              val validation = for {
                _ <- Either.cond(snapshot.ordinal === ordinal, (), "trusted_snapshot_ordinal_mismatch")
                _ <- Either.cond(signerSet.nonEmpty, (), "trusted_snapshot_proof_signers_empty")
                _ <- Either.cond(signerIds.size === signerSet.size, (), "trusted_snapshot_duplicate_signer")
                _ <- Either.cond(
                  seedlistPeerIds.isEmpty || signerSet.forall(seedlistPeerIds.contains),
                  (),
                  "trusted_snapshot_signer_not_seedlisted"
                )
                _ <- Either.cond(signatureValid, (), "trusted_snapshot_signature_invalid")
                _ <- Either.cond(contextProof === snapshot.stateProof, (), "trusted_snapshot_context_proof_mismatch")
              } yield ()

              validation.as(snapshot -> CurrencySnapshotContext(currencyAddress, info))
            }
          case _ =>
            s"trusted_snapshot_missing:${ordinal.value.value}"
              .asLeft[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotContext)]
              .pure[F]
        }
      }
    }

    def exactActivationParent(candidate: CurrencyConsensusOutcome): F[Either[String, TrustedParent]] = {
      val activation = config.certifiedConsensusActivationKey

      if (activation <= SnapshotOrdinal.MinIncrementalValue.value.value)
        "activation_parent_unavailable_at_genesis".asLeft[TrustedParent].pure[F]
      else if (activation > candidate.key.value.value)
        "activation_after_downloaded_candidate".asLeft[TrustedParent].pure[F]
      else {
        val activationKey = SnapshotOrdinal.unsafeApply(activation)
        val parentOrdinal = SnapshotOrdinal.unsafeApply(activation - 1L)

        locallyValidatedSnapshot(parentOrdinal).flatMap(_.traverse {
          case (snapshot, context) =>
            HasherSelector[F].withCurrent { implicit hasher =>
              for {
                snapshotHash <- snapshot.value.hash
                proofSigners = snapshot.proofs.toSortedSet.toList.map(_.id.toPeerId)
                legacy = CurrencyConsensusOutcome(
                  key = parentOrdinal,
                  facilitators = Facilitators(proofSigners),
                  removedFacilitators = RemovedFacilitators.empty,
                  withdrawnFacilitators = WithdrawnFacilitators.empty,
                  eligibleFacilitators = EligibleFacilitators(proofSigners),
                  finished = Finished(
                    snapshot,
                    Hash.empty,
                    context,
                    EventTrigger,
                    Candidates.empty,
                    Hash.empty,
                    snapshotHash
                  )
                )
                reset <- CurrencySnapshotConsensusStateCreator.resetLegacyOutcome[F](activationKey, legacy, config)
              } yield TrustedParent(reset, TrustedParentKind.AuthorizedRoot)
            }
        })
      }
    }

    def canonicalGenesisRoot: F[Either[String, TrustedParent]] =
      locallyValidatedSnapshot(CertifiedConsensusGenesis.FirstIncrementalOrdinal).flatMap {
        case Left(error) => error.asLeft[TrustedParent].pure[F]
        case Right((snapshot, context)) =>
          HasherSelector[F].withCurrent { implicit hasher =>
            snapshot.value.hash.map(hash =>
              TrustedParent(publicGenesisRoot(snapshot, context, hash), TrustedParentKind.AuthorizedRoot).asRight
            )
          }
      }

    def replayRoot(candidate: CurrencyConsensusOutcome): F[Either[String, TrustedParent]] =
      if (CertifiedConsensusGenesis.isActiveFromGenesis(config.certifiedConsensusActivationKey)) canonicalGenesisRoot
      else exactActivationParent(candidate)

    def loadPublicRounds(
      startExclusive: CurrencySnapshotKey,
      candidate: CurrencyConsensusOutcome
    ): F[Either[String, List[PublicRound]]] = {
      val terminal = BigInt(candidate.key.value.value)

      def loop(next: BigInt, acc: List[PublicRound]): F[Either[String, List[PublicRound]]] =
        if (next > terminal) acc.reverse.asRight[String].pure[F]
        else if (next > BigInt(Long.MaxValue)) "certified_lineage_key_overflow".asLeft[List[PublicRound]].pure[F]
        else {
          val ordinal = SnapshotOrdinal.unsafeApply(next.toLong)
          locallyValidatedSnapshot(ordinal).flatMap {
            case Left(error) => s"certified_public_round_missing:$error".asLeft[List[PublicRound]].pure[F]
            case Right((artifact, context)) =>
              val bindings = for {
                _ <- Either.cond(
                  if (ordinal === candidate.key)
                    Signed.sameValueAndProofs(artifact, candidate.finished.signedMajorityArtifact)
                  else true,
                  (),
                  "terminal_artifact_not_locally_validated"
                )
                _ <- Either.cond(
                  if (ordinal === candidate.key) context === candidate.finished.context else true,
                  (),
                  "terminal_context_not_locally_validated"
                )
              } yield ()

              bindings match {
                case Left(error) => error.asLeft[List[PublicRound]].pure[F]
                case Right(_)    => loop(next + 1, PublicRound(ordinal, artifact, context) :: acc)
              }
          }
        }

      loop(BigInt(startExclusive.value.value) + 1, List.empty)
    }

    def validateCarriedParentBinary(
      trusted: CurrencyConsensusOutcome,
      current: PublicRound
    ): F[Either[String, Unit]] =
      current.artifact.value.certifiedLineage match {
        case None =>
          Either
            .cond(trusted.finished.certifiedOutcome.isEmpty, (), "currency_parent_layer_evidence_missing")
            .pure[F]
        case Some(lineage) =>
          (lineage.parentLayerEvidence, trusted.finished.certifiedBinary).tupled match {
            case Some((evidence: CertifiedConsensus.CertifiedLayerEvidenceV1.Currency, trustedBinary)) =>
              val frozenCommittee = lineage.parentOutcome.proposalQc.value.roundStartFacilitators.toSortedSet.toSet
              HasherSelector[F].withCurrent { implicit hasher =>
                CertifiedConsensus
                  .reconstructAndVerifyCurrencyBinary[F, CurrencyIncrementalSnapshot](
                    trusted.finished.signedMajorityArtifact,
                    evidence,
                    trustedBinary.value.lastSnapshotHash,
                    frozenCommittee
                  )
                  .flatMap(result =>
                    result
                      .flatMap(reconstructed =>
                        Either.cond(
                          reconstructed.hash === trusted.finished.binaryArtifactHash &&
                            reconstructed.signed.value === trustedBinary.value,
                          (),
                          "currency_parent_binary_mismatch"
                        )
                      )
                      .pure[F]
                  )
              }
            case Some((_, _)) => "currency_parent_layer_evidence_wrong_domain".asLeft[Unit].pure[F]
            case None         => "currency_parent_binary_authority_missing".asLeft[Unit].pure[F]
          }
      }

    def advancePublicRound(
      trusted: CurrencyConsensusOutcome,
      round: PublicRound,
      authority: CertifiedConsensus.CertifiedLineageEvidenceV1
    ): F[Either[String, CurrencyConsensusOutcome]] =
      authority.parentLayerEvidence match {
        case Some(evidence: CertifiedConsensus.CertifiedLayerEvidenceV1.Currency) =>
          validateCarriedParentBinary(trusted, round).flatMap {
            case Left(error) => error.asLeft[CurrencyConsensusOutcome].pure[F]
            case Right(_)    =>
              // The first certified binary is the authority for the legacy/genesis binary parent
              // scalar only. Committee authority remains the independently derived root above.
              val trustedForRound =
                if (trusted.finished.certifiedOutcome.isEmpty && trusted.finished.certifiedBinary.isEmpty)
                  trusted.copy(finished = trusted.finished.copy(binaryArtifactHash = evidence.parentBinaryLastSnapshotHash))
                else trusted
              val frozenCommittee = authority.parentOutcome.proposalQc.value.roundStartFacilitators.toSortedSet.toSet

              HasherSelector[F].withCurrent { implicit hasher =>
                CertifiedConsensus
                  .reconstructAndVerifyCurrencyBinary[F, CurrencyIncrementalSnapshot](
                    round.artifact,
                    evidence,
                    trustedForRound.finished.binaryArtifactHash,
                    frozenCommittee
                  )
                  .flatMap {
                    case Left(error) => error.asLeft[CurrencyConsensusOutcome].pure[F]
                    case Right(binary) =>
                      trustedParentKind(trustedForRound, config.certifiedConsensusActivationKey) match {
                        case Left(error) => error.asLeft[CurrencyConsensusOutcome].pure[F]
                        case Right(kind) =>
                          stateFromTrustedParent(round.key, TrustedParent(trustedForRound, kind)).flatMap {
                            case Left(error) => error.asLeft[CurrencyConsensusOutcome].pure[F]
                            case Right(state) =>
                              stateAdvancer
                                .deriveCertifiedPublicRound(
                                  state,
                                  round.artifact,
                                  binary,
                                  round.context,
                                  authority.parentOutcome
                                )
                                .map(_.map(_._2))
                          }
                      }
                  }
              }
          }
        case Some(_) => "currency_lineage_layer_evidence_wrong_domain".asLeft[CurrencyConsensusOutcome].pure[F]
        case None    => "currency_lineage_layer_evidence_missing".asLeft[CurrencyConsensusOutcome].pure[F]
      }

    def replayFromPublicLineage(candidate: CurrencyConsensusOutcome): F[Either[String, Unit]] =
      HasherSelector[F].withCurrent { implicit hasher =>
        (candidate.finished.certifiedOutcome, candidate.finished.certifiedBinary).tupled match {
          case None => "certified_outcome_or_binary_missing".asLeft[Unit].pure[F]
          case Some((terminalOutcome, terminalBinary)) =>
            replayRoot(candidate).flatMap {
              case Left(error) => error.asLeft[Unit].pure[F]
              case Right(root) =>
                loadPublicRounds(root.outcome.key, candidate).flatMap {
                  case Left(error) => error.asLeft[Unit].pure[F]
                  case Right(rounds) =>
                    val terminalEvidence = CertifiedConsensus.CertifiedLineageEvidenceV1(
                      terminalOutcome,
                      CertifiedConsensus.currencyLayerEvidence(terminalBinary).some
                    )

                    CertifiedConsensus
                      .verifySequentialLineage[F, CurrencyConsensusOutcome, PublicRound](
                        trustedRoot = root.outcome,
                        trustedRootKey = root.outcome.key.value.value,
                        frames = rounds,
                        terminalEvidence = terminalEvidence.some,
                        domain = CertifiedConsensus.ConsensusDomain.CurrencyL0,
                        configuredFraction = config.quorumThresholdFraction,
                        keyOf = _.key.value.value,
                        lineageOf = _.artifact.value.certifiedLineage,
                        certifiedOutcomeOf = _.finished.certifiedOutcome
                      )(advancePublicRound)
                      .map(
                        _.flatMap(
                          _.lastOption
                            .toRight("certified_lineage_replay_empty")
                            .flatMap(derived => Either.cond(derived === candidate, (), "certified_outcome_derivation_mismatch"))
                        )
                      )
                }
            }
        }
      }

    def trustedLocalParent(candidate: CurrencyConsensusOutcome): F[Either[String, TrustedParent]] =
      if (candidate.key.value.value <= SnapshotOrdinal.MinValue.value.value)
        "trusted_predecessor_key_underflow".asLeft[TrustedParent].pure[F]
      else {
        val parentOrdinal = SnapshotOrdinal.unsafeApply(candidate.key.value.value - 1L)

        certifiedOutcomeSidecar.read(parentOrdinal).flatMap {
          case None => "trusted_predecessor_sidecar_missing".asLeft[TrustedParent].pure[F]
          case Some(parent) =>
            trustedParentKind(parent, config.certifiedConsensusActivationKey) match {
              case Left(error) => error.asLeft[TrustedParent].pure[F]
              case Right(kind) =>
                locallyValidatedSnapshot(parentOrdinal).flatMap {
                  case Left(error) => error.asLeft[TrustedParent].pure[F]
                  case Right((snapshot, context)) =>
                    HasherSelector[F].withCurrent { implicit hasher =>
                      snapshot.value.hash.flatMap { snapshotHash =>
                        val bindings = for {
                          _ <- Either.cond(parent.key === parentOrdinal, (), "trusted_predecessor_key_mismatch")
                          _ <- Either.cond(
                            Signed.sameValueAndProofs(parent.finished.signedMajorityArtifact, snapshot),
                            (),
                            "trusted_predecessor_artifact_mismatch"
                          )
                          _ <- Either.cond(parent.finished.context === context, (), "trusted_predecessor_context_mismatch")
                          _ <- Either.cond(parent.finished.snapshotHash === snapshotHash, (), "trusted_predecessor_hash_mismatch")
                        } yield ()

                        val rootValidation = kind match {
                          case TrustedParentKind.Certified => ().asRight[String].pure[F]
                          case TrustedParentKind.AuthorizedRoot =>
                            CurrencyCertifiedGenesisOutcome
                              .validateAgainstLocalArtifact[F](parent, snapshot, seedlistPeerIds)
                        }

                        rootValidation.map(_.productR(bindings).as(TrustedParent(parent, kind)))
                      }
                    }
                }
            }
        }
      }

    candidate => {
      val active = config.certifiedConsensusActiveAt(candidate.key.value.value)
      val genesisCompatibility =
        CertifiedConsensusGenesis.isRootKey(config.certifiedConsensusActivationKey, candidate.key) &&
          candidate.finished.certifiedOutcome.isEmpty

      if (!active) Async[F].unit
      else if (genesisCompatibility)
        locallyValidatedSnapshot(candidate.key).flatMap {
          case Left(error) =>
            new IllegalStateException(s"downloaded_certified_outcome_genesis:$error").raiseError[F, Unit]
          case Right((snapshot, _)) =>
            CurrencyCertifiedGenesisOutcome
              .validateAgainstLocalArtifact[F](candidate, snapshot, seedlistPeerIds)
              .flatMap(
                _.leftMap(error => new IllegalStateException(s"downloaded_certified_outcome_genesis:$error")).liftTo[F]
              )
        }
      else {
        def validateAgainst(parent: TrustedParent): F[Either[String, Unit]] =
          HasherSelector[F].withCurrent { implicit hasher =>
            CertifiedConsensus
              .verifyCarriedParentOutcome[F](
                candidate.finished.signedMajorityArtifact.value.certifiedLineage,
                parent.outcome.finished.certifiedOutcome,
                CertifiedConsensus.ConsensusDomain.CurrencyL0,
                config.quorumThresholdFraction
              )
              .flatMap {
                case Left(error) => error.asLeft[Unit].pure[F]
                case Right(_) =>
                  validateCarriedParentBinary(
                    parent.outcome,
                    PublicRound(candidate.key, candidate.finished.signedMajorityArtifact, candidate.finished.context)
                  ).flatMap {
                    case Left(error) => error.asLeft[Unit].pure[F]
                    case Right(_) =>
                      stateFromTrustedParent(candidate.key, parent).flatMap {
                        case Left(error)  => error.asLeft[Unit].pure[F]
                        case Right(state) => stateAdvancer.certifiedOutcomeAdoption(state, candidate).map(_.void)
                      }
                  }
              }
          }

        trustedLocalParent(candidate).flatMap {
          case Right(parent) => validateAgainst(parent)
          case Left(_)       => replayFromPublicLineage(candidate)
        }.flatMap(
          _.leftMap(error => new IllegalStateException(s"downloaded_certified_outcome_invalid:$error")).liftTo[F]
        )
      }
    }
  }
}
