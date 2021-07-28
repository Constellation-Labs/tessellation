package org.tessellation.snapshot

import cats.effect.{ContextShift, IO, Timer}
import fs2.Stream
import org.scalatest.GivenWhenThen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.tessellation.consensus.{L1Block, L1Transaction}

class L0PipelineTest extends AnyFreeSpec with GivenWhenThen with Matchers {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.global)

  "blocks aggregation into edges" in {
    Given("stream of blocks")

    val blocks: Stream[IO, L1Block] = Stream
      .range[IO](1, 100)
      .map(v => L1Transaction(v, "a", "b", "", 0))
      .map(Set(_))
      .map(L1Block)
      .take(5)

    And("stream of tips")

    val tips: Stream[IO, Int] = Stream
      .range[IO](1, 10)

    And("tip interval equal 2")
    val tipInterval = 2

    And("tip delay equal 5")
    val tipDelay = 5

    When("combining into edge")
    val result = L0Pipeline.edges(blocks, tips, tipInterval, tipDelay)

    Then(s"it returns edges with blocks of range ${tipInterval}")
    result.compile.toList.unsafeRunSync() shouldBe
      List(
        L0Edge(Set(L1Block(Set(L1Transaction(1, "a", "b", "", 0))), L1Block(Set(L1Transaction(2, "a", "b", "", 0))))),
        L0Edge(Set(L1Block(Set(L1Transaction(3, "a", "b", "", 0))), L1Block(Set(L1Transaction(4, "a", "b", "", 0)))))
      )
  }

  "empty edges" in {
    Given("empty stream of blocks")
    val blocks: Stream[IO, L1Block] = Stream.empty

    And("correct stream of tips")
    val tips: Stream[IO, Int] = Stream
      .range[IO](1, 10)

    val tipInterval = 2
    val tipDelay = 5

    When("combining into edge")
    val result = L0Pipeline.edges(blocks, tips, tipInterval, tipDelay)

    Then("it returns empty edges")
    result.compile.toList.unsafeRunSync() shouldBe
      List(
        L0Edge(Set()),
        L0Edge(Set())
      )
  }

  "snapshot creation" in {
    Given("stream of edges")
    val blocks: Stream[IO, L1Block] = Stream
      .range[IO](1, 100)
      .map(L1Transaction(_, "a", "b", "", 0))
      .map(Set(_))
      .map(L1Block)
      .take(5)

    val tips: Stream[IO, Int] = Stream
      .range[IO](1, 10)

    val tipInterval = 2
    val tipDelay = 5

    val edges = L0Pipeline.edges(blocks, tips, tipInterval, tipDelay)

    When("executing L0 pipeline")
    val result = L0Pipeline.runPipeline(edges)

    Then(s"it returns snapshots using given edges")
    result.compile.toList.unsafeRunSync().map(_.right.get) shouldBe
      edges.compile.toList.unsafeRunSync().map(edge => Snapshot(edge.blocks))
  }
}
