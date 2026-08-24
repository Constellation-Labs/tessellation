package io.constellationnetwork.dag.l0

import cats.effect.IO

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.dag.l0.cli.method
import io.constellationnetwork.dag.l0.domain.snapshot.recovery.{Gl0RecoverySeedCommittee, RecoveryCheckpoint}
import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensusGenesis
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand.RollbackStartPolicy
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ConsensusOperationalState, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import com.monovore.decline.Command
import eu.timepit.refined.auto._
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

  pureTest("an operator recovery seed replaces proof signers in canonical PeerId order") {
    val planned = SortedSet(peerC, self, peerB)

    expect.same(planned.toList, Main.rollbackBootstrapFacilitators(self, List(peerC), Some(planned)))
  }

  pureTest("ordinary rollback preserves operational history while a recovery seed flushes it") {
    val restored = ConsensusOperationalState.empty.copy(
      recentProofSizes = SortedMap(SnapshotOrdinal.unsafeApply(100L) -> 7)
    )

    expect.same(restored, Main.rollbackOperationalSeed(restored, recoveryOverrideActive = false)) &&
    expect.same(ConsensusOperationalState.empty, Main.rollbackOperationalSeed(restored, recoveryOverrideActive = true))
  }

  pureTest("recovery-seed committee size replaces historical proof count for bootstrap classification") {
    expect.same(11, Main.rollbackProofSize(snapshotProofSize = 11, recoveryCommitteeSize = None)) &&
    expect.same(3, Main.rollbackProofSize(snapshotProofSize = 11, recoveryCommitteeSize = Some(3)))
  }

  pureTest("recovery-seed collateral preflight treats an absent anchor balance as empty") {
    val required = Amount(100L)

    expect(!Main.rollbackAnchorHasCollateral(None, required)) &&
    expect(!Main.rollbackAnchorHasCollateral(Some(Balance(99L)), required)) &&
    expect(Main.rollbackAnchorHasCollateral(Some(Balance(100L)), required))
  }

  pureTest("recovery seed needs three legacy rounds before activation or starts a new certified epoch") {
    val activation = SnapshotOrdinal.unsafeApply(2000L)

    expect(Main.validateRecoverySeedActivationSpacing(SnapshotOrdinal.unsafeApply(1997L), activation).isRight) &&
    expect(Main.validateRecoverySeedActivationSpacing(SnapshotOrdinal.unsafeApply(1998L), activation).isLeft) &&
    expect(Main.validateRecoverySeedActivationSpacing(SnapshotOrdinal.unsafeApply(1999L), activation).isLeft) &&
    expect(Main.validateRecoverySeedActivationSpacing(activation, activation).isRight) &&
    expect(Main.validateRecoverySeedActivationSpacing(SnapshotOrdinal.unsafeApply(2001L), activation).isRight)
  }

  pureTest("recovery seed rejects only the ambiguous certified-from-genesis root boundary") {
    val root = CertifiedConsensusGenesis.FirstIncrementalOrdinal
    val successor = SnapshotOrdinal.unsafeApply(root.value.value + 1L)
    val futureActivation = SnapshotOrdinal.unsafeApply(2000L)

    expect(Main.validateRecoverySeedPublicDiscoverability(root, SnapshotOrdinal.MinValue).isLeft) &&
    expect(Main.validateRecoverySeedPublicDiscoverability(root, root).isLeft) &&
    expect(Main.validateRecoverySeedPublicDiscoverability(successor, SnapshotOrdinal.MinValue).isRight) &&
    expect(Main.validateRecoverySeedPublicDiscoverability(successor, root).isRight) &&
    expect(Main.validateRecoverySeedPublicDiscoverability(root, futureActivation).isRight)
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

  pureTest("unsigned recovery seed requires an incremental source and the exact rollback hash") {
    val source = io.constellationnetwork.dag.l0.infrastructure.snapshot.programs.RollbackLoader.Source
    val expected = Hash("55" * 32)

    expect(Main.validateRecoverySeedAnchorSource(source.Incremental).isRight) &&
    expect(Main.validateRecoverySeedAnchorSource(source.FullSnapshot).isLeft) &&
    expect(Main.validateRecoverySeedRollbackHash(expected, expected).isRight) &&
    expect(Main.validateRecoverySeedRollbackHash(expected, Hash("66" * 32)).isLeft)
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
