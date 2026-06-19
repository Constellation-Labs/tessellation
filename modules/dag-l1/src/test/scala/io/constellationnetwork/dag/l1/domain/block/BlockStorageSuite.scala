package io.constellationnetwork.dag.l1.domain.block

import cats.effect.IO
import cats.effect.std.Random

import io.constellationnetwork.dag.l1.domain.block.BlockStorage._
import io.constellationnetwork.schema.BlockReference
import io.constellationnetwork.schema.height.Height
import io.constellationnetwork.security.hash.ProofsHash

import eu.timepit.refined.auto._
import weaver.SimpleIOSuite

object BlockStorageSuite extends SimpleIOSuite {

  // Regression (B2): adjustToMajority must be all-or-nothing. Its precondition dry-run runs before any mutation, so if
  // a referenced block is not in the state the corresponding sub-step expects (e.g. local state drifted since the
  // lock-free reconciliation read), it aborts with UnexpectedBlockStatesWhenAdjustingToMajority and mutates NOTHING --
  // avoiding a partial, unrollback-able reconciliation that would wedge the node.
  test("adjustToMajority aborts atomically (mutates nothing) when a referenced block is in an unexpected state") {
    val hash = ProofsHash("aaaa")
    val ref = BlockReference(Height(10L), hash)
    // toMarkMajority expects an AcceptedBlock, but here the block is already a MajorityBlock -> precondition violation.
    val initial: Map[ProofsHash, StoredBlock] = Map(hash -> MajorityBlock(ref, 0L, Active))

    for {
      implicit0(r: Random[IO]) <- Random.scalaUtilRandom[IO]
      bs <- BlockStorage.make[IO](initial)
      outcome <- bs.adjustToMajority(toMarkMajority = Set((hash, 1L))).attempt
      after <- bs.getState()
    } yield
      expect.all(
        outcome.fold(_.isInstanceOf[UnexpectedBlockStatesWhenAdjustingToMajority], _ => false),
        after == initial
      )
  }

  test("adjustToMajority applies when all preconditions hold") {
    val hash = ProofsHash("bbbb")
    val ref = BlockReference(Height(10L), hash)
    val initial: Map[ProofsHash, StoredBlock] = Map(hash -> MajorityBlock(ref, 0L, Active))

    for {
      implicit0(r: Random[IO]) <- Random.scalaUtilRandom[IO]
      bs <- BlockStorage.make[IO](initial)
      _ <- bs.adjustToMajority(tipsToDeprecate = Set(hash))
      after <- bs.getState()
    } yield expect(after.get(hash).exists { case MajorityBlock(_, _, Deprecated) => true; case _ => false })
  }
}
