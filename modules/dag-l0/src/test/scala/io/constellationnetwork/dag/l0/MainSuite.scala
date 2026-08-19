package io.constellationnetwork.dag.l0

import cats.effect.IO

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.dag.l0.cli.method
import io.constellationnetwork.dag.l0.domain.snapshot.recovery.{Gl0RecoveryPlan, Gl0RecoverySeedCommittee, RecoveryCheckpoint}
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand.RollbackStartPolicy
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ConsensusOperationalState, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import com.monovore.decline.Command
import eu.timepit.refined.auto._
import fs2.io.file.Path
import weaver.SimpleIOSuite

object MainSuite extends SimpleIOSuite {

  private val self: PeerId = PeerId(Hex("aa" * 64))
  private val peerB: PeerId = PeerId(Hex("bb" * 64))
  private val peerC: PeerId = PeerId(Hex("cc" * 64))

  private val recoveryAnchor = RecoveryCheckpoint(
    network = "integrationnet",
    ordinal = SnapshotOrdinal.unsafeApply(1234L),
    snapshotHash = Hash("11" * 32)
  )

  private val recoveryPlan = Gl0RecoveryPlan(
    Gl0RecoveryPlan.CurrentProtocol,
    Gl0RecoveryPlan.CurrentFormatVersion,
    Hash("22" * 32),
    recoveryAnchor,
    self,
    SortedSet(self, peerB)
  )

  pureTest("rollback bootstrap preserves snapshot proof signers when self signed the checkpoint") {
    val signers = List(peerB, self, peerC)

    expect.same(signers, Main.rollbackBootstrapFacilitators(self, signers))
  }

  pureTest("rollback bootstrap preserves snapshot proof signers when self did not sign the checkpoint") {
    val signers = List(peerB, peerC)

    expect.same(signers, Main.rollbackBootstrapFacilitators(self, signers))
  }

  pureTest("rollback bootstrap falls back to self-only only when checkpoint has no proof signers") {
    val signers = List.empty[PeerId]

    expect.same(List(self), Main.rollbackBootstrapFacilitators(self, signers))
  }

  pureTest("normal established rollback requires an anchor-signer lead and the exact aligned committee") {
    val committee = SortedSet(self, peerB, peerC)

    expect.same(
      Right(RollbackStartPolicy.RequireOutcomeAlignedQuorum(committee)),
      Main.normalRollbackStartPolicy(self, committee, postBootstrap = true)
    ) &&
    expect(
      Main
        .normalRollbackStartPolicy(PeerId(Hex("dd" * 64)), committee, postBootstrap = true)
        .left
        .exists(_.isInstanceOf[Main.NormalRollbackLeadNotInAnchorCommittee])
    )
  }

  pureTest("true-bootstrap rollback retains the legacy delayed start even when the lead is outside proof signers") {
    val committee = SortedSet(peerB, peerC)

    expect.same(
      Right(RollbackStartPolicy.LegacyDeferred),
      Main.normalRollbackStartPolicy(self, committee, postBootstrap = false)
    )
  }

  pureTest("an operator recovery plan replaces proof signers in canonical PeerId order") {
    val planned = SortedSet(peerC, self, peerB)

    expect.same(planned.toList, Main.rollbackBootstrapFacilitators(self, List(peerC), Some(planned)))
  }

  pureTest("ordinary rollback preserves operational history while a recovery plan flushes it") {
    val restored = ConsensusOperationalState.empty.copy(
      recentProofSizes = SortedMap(SnapshotOrdinal.unsafeApply(100L) -> 7)
    )

    expect.same(restored, Main.rollbackOperationalSeed(restored, recoveryPlanActive = false)) &&
    expect.same(ConsensusOperationalState.empty, Main.rollbackOperationalSeed(restored, recoveryPlanActive = true))
  }

  pureTest("recovery-plan committee size replaces historical proof count for bootstrap classification") {
    expect.same(11, Main.rollbackProofSize(snapshotProofSize = 11, plannedCommitteeSize = None)) &&
    expect.same(3, Main.rollbackProofSize(snapshotProofSize = 11, plannedCommitteeSize = Some(3)))
  }

  pureTest("recovery-plan collateral preflight treats an absent anchor balance as empty") {
    val required = Amount(100L)

    expect(!Main.rollbackAnchorHasCollateral(None, required)) &&
    expect(!Main.rollbackAnchorHasCollateral(Some(Balance(99L)), required)) &&
    expect(Main.rollbackAnchorHasCollateral(Some(Balance(100L)), required))
  }

  pureTest("a recovery plan accepts an absent or exactly matching seedlist-majority checkpoint") {
    expect(Main.validateRecoveryAnchorCompatibility(recoveryPlan, None).isRight) &&
    expect(Main.validateRecoveryAnchorCompatibility(recoveryPlan, Some(recoveryAnchor)).isRight)
  }

  pureTest("a recovery plan rejects every conflicting seedlist-majority checkpoint anchor dimension") {
    val wrongNetwork = recoveryAnchor.copy(network = "mainnet")
    val wrongOrdinal = recoveryAnchor.copy(ordinal = SnapshotOrdinal.unsafeApply(recoveryAnchor.ordinal.value.value + 1L))
    val wrongHash = recoveryAnchor.copy(snapshotHash = Hash("33" * 32))

    expect(Main.validateRecoveryAnchorCompatibility(recoveryPlan, Some(wrongNetwork)).isLeft) &&
    expect(Main.validateRecoveryAnchorCompatibility(recoveryPlan, Some(wrongOrdinal)).isLeft) &&
    expect(Main.validateRecoveryAnchorCompatibility(recoveryPlan, Some(wrongHash)).isLeft)
  }

  pureTest("an unsigned recovery seed is bound to the same independent checkpoint comparison") {
    expect(Main.validateRecoverySeedAnchorCompatibility(recoveryAnchor, None).isRight) &&
    expect(Main.validateRecoverySeedAnchorCompatibility(recoveryAnchor, Some(recoveryAnchor)).isRight) &&
    expect(
      Main
        .validateRecoverySeedAnchorCompatibility(recoveryAnchor.copy(snapshotHash = Hash("44" * 32)), Some(recoveryAnchor))
        .isLeft
    )
  }

  pureTest("signed plan and unsigned env recovery inputs are mutually exclusive") {
    expect(Main.validateRecoveryConfigurationExclusive(recoveryPlanConfigured = false, recoverySeedConfigured = false).isRight) &&
    expect(Main.validateRecoveryConfigurationExclusive(recoveryPlanConfigured = true, recoverySeedConfigured = false).isRight) &&
    expect(Main.validateRecoveryConfigurationExclusive(recoveryPlanConfigured = false, recoverySeedConfigured = true).isRight) &&
    expect(Main.validateRecoveryConfigurationExclusive(recoveryPlanConfigured = true, recoverySeedConfigured = true).isLeft)
  }

  pureTest("unsigned recovery next-seat headroom counts only selected proof signers") {
    val seed = Gl0RecoverySeedCommittee.parse(s"${self.value.value},${peerB.value.value},${peerC.value.value}").toOption.get
    val foreign = PeerId(Hex("dd" * 64))
    val pending = Main.recoverySeedHeadroom(seed, Set(self, peerB, foreign), 2.0 / 3.0)
    val ready = Main.recoverySeedHeadroom(seed, Set(self, peerB, peerC, foreign), 2.0 / 3.0)

    expect.same(2, pending.observed) &&
    expect.same(3, pending.required) &&
    expect.same(SortedSet(peerC), pending.absent) &&
    expect.same(1, pending.deficit) &&
    expect(!pending.isReady) &&
    expect.same(3, ready.observed) &&
    expect.same(0, ready.deficit) &&
    expect(ready.isReady)
  }

  pureTest("headroom deficit is quorum deficit rather than all absent selected members") {
    val peerD = PeerId(Hex("dd" * 64))
    val peerE = PeerId(Hex("ee" * 64))
    val seed = Gl0RecoverySeedCommittee
      .parse(s"${self.value.value},${peerB.value.value},${peerC.value.value},${peerD.value.value},${peerE.value.value}")
      .toOption
      .get
    val headroom = Main.recoverySeedHeadroom(seed, Set(self, peerB, peerC, peerD), 2.0 / 3.0)

    expect(headroom.isReady) &&
    expect.same(0, headroom.deficit) &&
    expect.same(SortedSet(peerE), headroom.absent)
  }

  pureTest("recovery-plan v1 accepts incremental anchors and rejects full-snapshot sources") {
    val source = io.constellationnetwork.dag.l0.infrastructure.snapshot.programs.RollbackLoader.Source

    expect(Main.validateRecoveryAnchorSource(source.Incremental).isRight) &&
    expect(Main.validateRecoveryAnchorSource(source.FullSnapshot).isLeft)
  }

  pureTest("unsigned recovery seed requires an incremental source and the exact rollback hash") {
    val source = io.constellationnetwork.dag.l0.infrastructure.snapshot.programs.RollbackLoader.Source
    val expected = Hash("55" * 32)

    expect(Main.validateRecoverySeedAnchorSource(source.Incremental).isRight) &&
    expect(Main.validateRecoverySeedAnchorSource(source.FullSnapshot).isLeft) &&
    expect(Main.validateRecoverySeedRollbackHash(expected, expected).isRight) &&
    expect(Main.validateRecoverySeedRollbackHash(expected, Hash("66" * 32)).isLeft)
  }

  test("GL0 recovery-plan CLI option is inert by default and requires an explicit path") {
    val command = Command("dag-l0-test", "test parser")(method.RunRollback.recoveryPlanPathOpts)
    val path = Path("/tmp/reviewed-gl0-recovery-plan.json")

    IO(
      expect.same(Some(None), command.parse(Seq.empty).toOption) &&
        expect.same(Some(Some(path)), command.parse(Seq("--recovery-plan", path.toString)).toOption)
    )
  }

  test("GL0 recovery seed is env-only and inert when the env is absent") {
    val command = Command("dag-l0-test", "test parser")(method.RunRollback.recoverySeedCommitteeOpts)
    val parsed = Gl0RecoverySeedCommittee.parse(s"${peerC.value.value},${self.value.value},${peerB.value.value}")

    IO(
      expect.same(Some(None), command.parse(Seq.empty).toOption) &&
        expect(command.parse(Seq("--recovery-seed-committee", peerB.value.value)).isLeft) &&
        expect.same(Some(SortedSet(self, peerB, peerC)), parsed.toOption.map(_.committee))
    )
  }
}
