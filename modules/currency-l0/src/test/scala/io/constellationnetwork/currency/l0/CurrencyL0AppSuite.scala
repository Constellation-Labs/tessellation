package io.constellationnetwork.currency.l0

import cats.data.NonEmptySet
import cats.effect.IO

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration._

import io.constellationnetwork.currency.l0.cli.method
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusPeerController.{AdmissionInput, AdmissionSizing}
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{AdmissionReason, AdmissionVote}
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.AdmissionCertificateBuilder
import io.constellationnetwork.node.shared.infrastructure.consensus.state.QuorumPolicy
import io.constellationnetwork.node.shared.infrastructure.consensus.{CommitteeBuilder, ConsensusPeerController}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import com.monovore.decline.Command
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import weaver.SimpleIOSuite

object CurrencyL0AppSuite extends SimpleIOSuite {

  private val self: PeerId = PeerId(Hex("aa" * 64))
  private val peerB: PeerId = PeerId(Hex("bb" * 64))
  private val peerC: PeerId = PeerId(Hex("cc" * 64))
  private val facilitatorsHash: Hash = Hash.fromBytes("FACILITATORS".getBytes("UTF-8"))
  private val lastSnapshotHash: Hash = Hash.fromBytes("LAST_SNAPSHOT".getBytes("UTF-8"))

  // Mirrors the Currency L0 IntegrationNet values relevant to AdmissionSizing.from:
  // activeFacilitatorFloor defaults to 4, coreCommitteeSize resolves to 9,
  // consensus.maxFacilitatorCount is 20, and active target/max are not configured.
  private val integrationNetCoreFloor = 9
  private val integrationNetConsensusConfig =
    ConsensusConfig(
      timeTriggerInterval = 10.seconds,
      declarationTimeout = 10.seconds,
      declarationRangeLimit = 100L,
      lockDuration = 10.seconds,
      eventCutter = EventCutterConfig(
        maxBinarySizeBytes = PosInt(1024),
        maxUpdateNodeParametersSize = PosInt(1024)
      ),
      maxFacilitatorCount = Some(PosInt(20)),
      activeFacilitatorFloor = 4,
      activeFacilitatorTarget = None,
      activeFacilitatorMax = None
    )

  test("rollback preserves proof-signer order by default when self signed the checkpoint") {
    val signers = List(peerC, self, peerB)

    IO(
      expect.same(
        signers,
        CurrencyL0App.rollbackBootstrapFacilitators(self, signers, allowSoloConsensus = false)
      )
    )
  }

  test("rollback preserves the existing self-only fallback when self did not sign the checkpoint") {
    val signers = List(peerB, peerC)

    IO(
      expect.same(
        List(self),
        CurrencyL0App.rollbackBootstrapFacilitators(self, signers, allowSoloConsensus = false)
      )
    )
  }

  test("allow-solo-consensus forces a self-only bootstrap despite multiple proof signers") {
    val signers = List(self, peerB, peerC)

    IO(
      expect.same(
        List(self),
        CurrencyL0App.rollbackBootstrapFacilitators(self, signers, allowSoloConsensus = true)
      )
    )
  }

  test("allow-solo-consensus CLI option defaults off and requires the explicit flag") {
    val command = Command("currency-l0-test", "test parser")(method.RunRollback.allowSoloConsensusOpts)

    IO(
      expect.same(Some(false), command.parse(Seq.empty).toOption) &&
        expect.same(Some(true), command.parse(Seq("--allow-solo-consensus")).toOption)
    )
  }

  test("a singleton rollback committee can form an admission certificate from its own vote") {
    val singleton = CurrencyL0App.rollbackBootstrapFacilitators(
      self,
      List(self, peerB, peerC),
      allowSoloConsensus = true
    )
    val admissionQuorum = QuorumPolicy.fromFraction(singleton.size, fraction = 1.0)
    val vote =
      Signed(
        AdmissionVote(
          targetPeer = peerB,
          reason = AdmissionReason.ReadyAtTip,
          facilitatorsHash = facilitatorsHash,
          lastSnapshotHash = lastSnapshotHash
        ),
        NonEmptySet.of(SignatureProof(Id(self.value), Signature(Hex("00"))))
      )
    val certificate =
      AdmissionCertificateBuilder.build(
        target = peerB,
        reason = AdmissionReason.ReadyAtTip,
        facilitatorsHash = facilitatorsHash,
        lastSnapshotHash = lastSnapshotHash,
        votes = Map(self -> vote),
        quorumSize = admissionQuorum,
        witnessPool = singleton.toSet
      )
    val excludedVoter =
      AdmissionCertificateBuilder.build(
        target = peerB,
        reason = AdmissionReason.ReadyAtTip,
        facilitatorsHash = facilitatorsHash,
        lastSnapshotHash = lastSnapshotHash,
        votes = Map(self -> vote),
        quorumSize = admissionQuorum,
        witnessPool = Set.empty
      )

    IO(
      expect.same(1, admissionQuorum) &&
        expect(certificate.exists(_.votes.length == 1), s"singleton vote should form a certificate: $certificate") &&
        expect(
          excludedVoter.swap.exists(_.code.startsWith("under_quorum")),
          s"the same vote must not count outside the witness pool: $excludedVoter"
        )
    )
  }

  test("certified returning peers are retained and promoted below the IntegrationNet emergency floor") {
    val singleton = CurrencyL0App.rollbackBootstrapFacilitators(
      self,
      List(self, peerB, peerC),
      allowSoloConsensus = true
    )
    val afterFirstCertificate = ConsensusPeerController.applyCertifiedAdmissions(singleton, List(peerB))
    val afterSecondCertificate = ConsensusPeerController.applyCertifiedAdmissions(afterFirstCertificate, List(peerC))
    val firstCommittees = committeesFor(afterFirstCertificate)
    val secondCommittees = committeesFor(afterSecondCertificate)
    val resolvedSizing =
      AdmissionSizing.from(
        integrationNetConsensusConfig,
        coreCommitteeSize = integrationNetCoreFloor,
        selectedSize = afterSecondCertificate.size
      )

    IO(
      expect.same(AdmissionSizing(4, 9, 20), resolvedSizing) &&
        expect.same(List(self, peerB), firstCommittees._1) &&
        expect.same(List(self, peerB), firstCommittees._2) &&
        expect.same(List(self, peerB, peerC), secondCommittees._1) &&
        expect.same(List(self, peerB, peerC), secondCommittees._2)
    )
  }

  private def committeesFor(selected: List[PeerId]): (List[PeerId], List[PeerId]) = {
    val active = ConsensusPeerController
      .chooseActive(
        AdmissionInput(
          selected = selected,
          recentSigners = SortedMap.empty[SnapshotOrdinal, SortedSet[PeerId]],
          latestRoundStartFacilitators = Set.empty,
          peerQuality = Map.empty,
          activeScores = Map.empty,
          sizing = AdmissionSizing.from(
            integrationNetConsensusConfig,
            coreCommitteeSize = integrationNetCoreFloor,
            selectedSize = selected.size
          ),
          minParticipationObservations = 10,
          minParticipationRatio = 0.5,
          config = ConsensusPeerController.Config.default
        )
      )
      .active
    val core = CommitteeBuilder
      .build(
        candidates = active,
        priorTiers = SortedMap.empty,
        peerQuality = Map.empty,
        coreFloor = integrationNetCoreFloor,
        minObservations = 10,
        minRatio = 0.5
      )
      .core

    active -> core
  }
}
