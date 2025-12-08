package io.constellationnetwork.security

import java.util.UUID

import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.util.Random

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import weaver.MutableIOSuite

object HasherPerformanceSuite extends MutableIOSuite {

  type Res = JsonSerializer[IO]

  override def sharedResource: Resource[IO, Res] =
    JsonSerializer.forAsync[IO].asResource

  @derive(encoder, decoder)
  case class SubData(
    field1: String,
    field2: Int,
    field3: List[String]
  )

  @derive(encoder, decoder)
  case class NestedData(
    value: String,
    numbers: List[Double],
    subData: Option[SubData]
  )

  @derive(encoder, decoder)
  case class LargeObject(
    id: String,
    data: List[NestedData],
    metadata: Map[String, String]
  )

  def generateLargeObject(sizeMB: Double): LargeObject = {
    val random = new Random(42)
    val targetBytes = (sizeMB * 1024 * 1024).toInt
    val estimatedStringSize = 100
    val numItems = targetBytes / estimatedStringSize

    val dataItems = (1 to numItems).map { _ =>
      NestedData(
        value = s"value_${UUID.randomUUID()}",
        numbers = List.fill(10)(random.nextDouble()),
        subData = Some(
          SubData(
            field1 = s"field_${UUID.randomUUID()}",
            field2 = random.nextInt(10000),
            field3 = List.fill(5)(UUID.randomUUID().toString)
          )
        )
      )
    }.toList

    LargeObject(
      id = UUID.randomUUID().toString,
      data = dataItems,
      metadata = (1 to 100).map(i => s"key_$i" -> s"value_${UUID.randomUUID()}").toMap
    )
  }

  test("cached hasher produces consistent hashes for same object") { implicit json =>
    implicit val hasher: Hasher[IO] = Hasher.forJsonCached[IO]
    val testObject = generateLargeObject(0.1)
    val iterations = 10

    (1 to iterations).toList
      .traverse(_ => hasher.hash(testObject))
      .map { hashes =>
        expect(hashes.distinct.size == 1, s"Expected all hashes to be equal, got ${hashes.distinct.size} distinct values")
      }
  }

  test("uncached hasher produces consistent hashes for same object") { implicit json =>
    implicit val hasher: Hasher[IO] = Hasher.forJsonUncached[IO]
    val testObject = generateLargeObject(0.1)
    val iterations = 10

    (1 to iterations).toList
      .traverse(_ => hasher.hash(testObject))
      .map { hashes =>
        expect(hashes.distinct.size == 1, s"Expected all hashes to be equal, got ${hashes.distinct.size} distinct values")
      }
  }

  test("cached and uncached hashers produce identical hashes") { implicit json =>
    val cachedHasher = Hasher.forJsonCached[IO]
    val uncachedHasher = Hasher.forJsonUncached[IO]
    val testObject = generateLargeObject(0.1)

    for {
      cachedHash <- cachedHasher.hash(testObject)
      uncachedHash <- uncachedHasher.hash(testObject)
    } yield expect.eql(cachedHash, uncachedHash)
  }

  test("cached hasher is faster than uncached for repeated hashes") { implicit json =>
    val cachedHasher = Hasher.forJsonCached[IO]
    val uncachedHasher = Hasher.forJsonUncached[IO]
    val testObject = generateLargeObject(0.5)
    val iterations = 20

    for {
      _ <- cachedHasher.hash(testObject)
      _ <- uncachedHasher.hash(testObject)

      cachedTimed <- IO.monotonic.flatMap { start =>
        (1 to iterations).toList.traverse(_ => cachedHasher.hash(testObject)) >>
          IO.monotonic.map(end => (end - start).toMillis)
      }

      uncachedTimed <- IO.monotonic.flatMap { start =>
        (1 to iterations).toList.traverse(_ => uncachedHasher.hash(testObject)) >>
          IO.monotonic.map(end => (end - start).toMillis)
      }

      _ <- IO.println(s"Cached: ${cachedTimed}ms total (${cachedTimed.toDouble / iterations}ms avg)")
      _ <- IO.println(s"Uncached: ${uncachedTimed}ms total (${uncachedTimed.toDouble / iterations}ms avg)")
      _ <- IO.println(s"Speedup: ${uncachedTimed.toDouble / cachedTimed}x faster with cache")
    } yield expect(cachedTimed < uncachedTimed, s"Expected cached ($cachedTimed ms) < uncached ($uncachedTimed ms)")
  }

  test("performance comparison across different object sizes") { implicit json =>
    val cachedHasher = Hasher.forJsonCached[IO]
    val uncachedHasher = Hasher.forJsonUncached[IO]
    val testSizes = List(0.1, 0.5, 1.0)
    val iterations = 10

    testSizes.traverse { sizeMB =>
      val testObject = generateLargeObject(sizeMB)

      for {
        _ <- cachedHasher.hash(testObject)
        _ <- uncachedHasher.hash(testObject)

        cachedResults <- (1 to iterations).toList.traverse { _ =>
          IO.monotonic.flatMap { start =>
            cachedHasher.hash(testObject).flatMap { hash =>
              IO.monotonic.map(end => ((end - start).toNanos, hash))
            }
          }
        }

        uncachedResults <- (1 to iterations).toList.traverse { _ =>
          IO.monotonic.flatMap { start =>
            uncachedHasher.hash(testObject).flatMap { hash =>
              IO.monotonic.map(end => ((end - start).toNanos, hash))
            }
          }
        }

        cachedAvg = cachedResults.map(_._1).sum.toDouble / iterations / 1_000_000
        uncachedAvg = uncachedResults.map(_._1).sum.toDouble / iterations / 1_000_000
        speedup = uncachedAvg / cachedAvg

        allHashesEqual = cachedResults.map(_._2).toSet.size == 1 &&
          uncachedResults.map(_._2).toSet.size == 1 &&
          cachedResults.head._2 == uncachedResults.head._2

        _ <- IO.println(s"Size: ${sizeMB}MB - Cached: ${f"$cachedAvg%.2f"}ms, Uncached: ${f"$uncachedAvg%.2f"}ms, Speedup: ${f"$speedup%.2f"}x, Consistent: $allHashesEqual")

      } yield expect(allHashesEqual, s"Hashes should be consistent for ${sizeMB}MB object")
    }.map(_.combineAll)
  }
}