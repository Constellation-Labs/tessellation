package io.constellationnetwork.dag.l0

import cats.effect.IO

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.dag.l0.cli.method
import io.constellationnetwork.dag.l0.domain.snapshot.recovery.{Gl0RecoveryPlan, RecoveryCheckpoint}
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ConsensusOperationalState, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.{JsonHash, KryoHash}

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

  pureTest("recovery-plan activation spacing rejects A-1 and A-2, accepts A-3, and accepts a fresh epoch at or after A") {
    val activation = SnapshotOrdinal.unsafeApply(2000L)
    def at(value: Long) = recoveryPlan.copy(anchor = recoveryAnchor.copy(ordinal = SnapshotOrdinal.unsafeApply(value)))

    expect(Main.validateRecoveryActivationSpacing(at(1999L), activation).isLeft) &&
    expect(Main.validateRecoveryActivationSpacing(at(1998L), activation).isLeft) &&
    expect(Main.validateRecoveryActivationSpacing(at(1997L), activation).isRight) &&
    expect(Main.validateRecoveryActivationSpacing(at(2000L), activation).isRight) &&
    expect(Main.validateRecoveryActivationSpacing(at(2001L), activation).isRight)
  }

  pureTest("recovery-plan v1 accepts JSON anchors and rejects ambiguous historical Kryo anchors") {
    expect(Main.validateRecoveryAnchorHashLogic(recoveryPlan, JsonHash).isRight) &&
    expect(Main.validateRecoveryAnchorHashLogic(recoveryPlan, KryoHash).isLeft)
  }

  pureTest("ordinary rollback is unchanged before v35 and requires a signed plan at or after activation") {
    val activation = SnapshotOrdinal.unsafeApply(2000L)

    expect(Main.validateOrdinaryRollbackAnchor(SnapshotOrdinal.unsafeApply(1999L), activation).isRight) &&
    expect(Main.validateOrdinaryRollbackAnchor(activation, activation).isLeft) &&
    expect(Main.validateOrdinaryRollbackAnchor(SnapshotOrdinal.unsafeApply(2001L), activation).isLeft)
  }

  test("GL0 recovery-plan CLI option is inert by default and requires an explicit path") {
    val command = Command("dag-l0-test", "test parser")(method.RunRollback.recoveryPlanPathOpts)
    val path = Path("/tmp/reviewed-gl0-recovery-plan.json")

    IO(
      expect.same(Some(None), command.parse(Seq.empty).toOption) &&
        expect.same(Some(Some(path)), command.parse(Seq("--recovery-plan", path.toString)).toOption)
    )
  }
}
