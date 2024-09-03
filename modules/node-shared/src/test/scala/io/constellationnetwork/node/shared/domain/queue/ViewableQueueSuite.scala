package io.constellationnetwork.node.shared.domain.queue

import cats.effect.IO
import cats.effect.implicits._
import cats.syntax.all._

import scala.concurrent.duration.DurationDouble

import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object ViewableQueueSuite extends SimpleIOSuite with Checkers {

  test("Offer and take with single element") {
    forall(Gen.chooseNum(1, 1000)) { item =>
      for {
        viewQueue <- ViewableQueue.make[IO, Int]
        _ <- viewQueue.offer(item)
        takenItem <- viewQueue.take
        remainingItems <- viewQueue.view
      } yield expect(takenItem == item) && expect(remainingItems.isEmpty)
    }
  }

  test("Insert multiple items at once using tryOfferN") {
    forall(Gen.listOfN(100, Gen.chooseNum(1, 1000))) { items =>
      for {
        viewQueue <- ViewableQueue.make[IO, Int]
        _ <- viewQueue.tryOfferN(items)
        viewResult <- viewQueue.view
      } yield expect(viewResult.sorted == items.sorted)
    }
  }

  test("Take a single item and validate the remaining queue") {
    forall(Gen.listOfN(100, Gen.chooseNum(1, 1000)).map(_.distinct)) { items =>
      for {
        viewQueue <- ViewableQueue.make[IO, Int]
        _ <- items.traverse(viewQueue.offer)
        takenItem <- viewQueue.take
        remainingItems <- viewQueue.view
      } yield expect(remainingItems.size == items.size - 1).and(expect(!remainingItems.contains(takenItem)))
    }
  }

  test("Insert and retrieve many Int from single fiber") {
    forall(Gen.chooseNum(0, 20000)) { numItems =>
      for {
        viewQueue <- ViewableQueue.make[IO, Int]
        _ <- List.range(0, numItems).map(i => viewQueue.offer(i)).parSequence
        viewResult <- viewQueue.view
        takeResult <- viewQueue.tryTakeN(Some(numItems))
      } yield expect(viewResult.sorted == takeResult.sorted)
    }
  }

  test("Insert and retrieve many Int via multiple fibers") {

    val gen = for {
      numFibers <- Gen.chooseNum(1, 100)
      numItems <- Gen.chooseNum(0, 200)
    } yield (numFibers, numItems)

    forall(gen) {
      case (numFibers, numItems) =>
        for {
          viewQueue <- ViewableQueue.make[IO, Int]
          actions = List
            .range(0, numFibers)
            .map(i => List.range(i * numItems, (i + 1) * numItems))
            .flatMap(list => list.map(item => IO.sleep((math.random() * 2).millisecond) *> viewQueue.offer(item)))
          fibers <- actions.parTraverseN(numFibers)(_.start)
          _ <- fibers.traverse(_.join)
          viewResult <- viewQueue.view
          takeResult <- viewQueue.tryTakeN(Some(numFibers * numItems))
        } yield expect(viewResult.sorted == takeResult.sorted)
    }
  }

  test("Take when the queue is empty") {
    forall(Gen.const(())) { _ =>
      for {
        viewQueue <- ViewableQueue.make[IO, Int]
        _ <- viewQueue.take.attempt
      } yield expect(true)
    }
  }

  test("tryTakeN edge cases") {
    val gen = for {
      items <- Gen.listOfN(100, Gen.chooseNum(1, 1000))
      n <- Gen.oneOf(0, 1, items.size + 1)
    } yield (items, n)

    forall(gen) {
      case (items, n) =>
        for {
          viewQueue <- ViewableQueue.make[IO, Int]
          _ <- items.traverse(viewQueue.offer)
          takenItems <- viewQueue.tryTakeN(Some(n))
        } yield
          if (n <= items.size) expect(takenItems.size == n)
          else expect(takenItems.size == items.size) // If n is larger than the queue size, it should return all items.
    }
  }

  test("Multi-step interaction between offer, take and tryOfferN with single element") {
    forall(Gen.chooseNum(1, 1000)) { item =>
      for {
        viewQueue <- ViewableQueue.make[IO, Int]
        _ <- viewQueue.offer(item)
        _ <- viewQueue.tryOfferN(List(item))
        takenItem <- viewQueue.take
      } yield expect(takenItem == item)
    }
  }
}
