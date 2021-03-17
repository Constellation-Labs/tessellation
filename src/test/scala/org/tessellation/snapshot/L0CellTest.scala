package org.tessellation.snapshot

import org.scalatest.GivenWhenThen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.tessellation.consensus.{L1Block, L1Transaction}
import org.tessellation.schema.Transaction

class L0CellTest extends AnyFreeSpec with GivenWhenThen with Matchers {
  "snapshot creation" in {
    Given("edge with blocks")
    val tx1 = L1Transaction(1, "a", "b", "", 0)
    val tx2 = L1Transaction(2, "a", "b", tx1.hash, 1)
    val block1 = L1Block(Set(tx1))
    val block2 = L1Block(Set(tx2))

    val edge = L0Edge(Set(block1, block2))

    When("executing L0Cell.run")
    val result = L0Cell(edge).run()

    Then("it returns a snapshot with given blocks")
    result.unsafeRunSync() shouldBe Right(Snapshot(Set(block1, block2)))
  }
}
