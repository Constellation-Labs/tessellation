package io.constellationnetwork.security.mpt

import cats.effect.IO
import cats.syntax.traverse._

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex

import io.circe.Encoder
import io.circe.syntax._
import org.scalacheck.Gen

object MPTGenerators {

  def hexGen: Gen[Hex] =
    Gen.listOfN(64, Gen.hexChar).map(chars => Hex(chars.mkString))

  def hexKeyValueGen[A: Encoder](valueGen: Gen[A])(implicit H: Hasher[IO]): Gen[IO[(Hex, A)]] =
    valueGen.map { value =>
      H.hash(value.asJson).map(hash => Hex(hash.value) -> value)
    }

  def leafMapGen[A: Encoder](
    size: Int,
    valueGen: Gen[A]
  )(implicit H: Hasher[IO]): Gen[IO[Map[Hex, A]]] =
    Gen.listOfN(size, hexKeyValueGen(valueGen)).map { gens =>
      gens.sequence.map(_.toMap)
    }

  def twoDisjointLeafMapsGen[A: Encoder](
    size1: Int,
    size2: Int,
    valueGen: Gen[A]
  )(implicit H: Hasher[IO]): Gen[IO[(Map[Hex, A], Map[Hex, A])]] = for {
    map1Gen <- leafMapGen(size1, valueGen)
    map2Gen <- leafMapGen(size2, valueGen)
  } yield
    for {
      map1 <- map1Gen
      map2 <- map2Gen
      disjoint = map2.filterNot { case (k, _) => map1.contains(k) }
    } yield (map1, disjoint)

  def prefixHexGen(prefix: String): Gen[Hex] = for {
    suffix <- Gen.listOfN(64 - prefix.length, Gen.hexChar)
  } yield Hex(prefix + suffix.mkString)

  def prefixedLeafMapGen[A: Encoder](
    prefix: String,
    size: Int,
    valueGen: Gen[A]
  )(implicit H: Hasher[IO]): Gen[IO[Map[Hex, A]]] = for {
    values <- Gen.listOfN(size, valueGen)
    prefixedHexes <- Gen.listOfN(size, prefixHexGen(prefix))
  } yield
    values
      .zip(prefixedHexes)
      .traverse {
        case (value, hex) => IO.pure(hex -> value)
      }
      .map(_.toMap)

  def rangeHexGen(start: String, end: String): Gen[Hex] = {
    require(start.length == end.length, "Start and end must have same length")
    require(start < end, "Start must be less than end")

    val startNum = BigInt(start, 16)
    val endNum = BigInt(end, 16)

    Gen.chooseNum(startNum.toLong, endNum.toLong).map { num =>
      val hexString = BigInt(num).toString(16).toUpperCase.reverse.padTo(start.length, '0').reverse.mkString
      Hex(hexString.toLowerCase)
    }
  }

  def rangedLeafMapGen[A: Encoder](
    startHex: Hex,
    endHex: Hex,
    size: Int,
    valueGen: Gen[A]
  )(implicit H: Hasher[IO]): Gen[IO[List[(Hex, A)]]] = {
    val start = startHex.value
    val end = endHex.value

    for {
      values <- Gen.listOfN(size, valueGen)
    } yield
      values.traverse { value =>
        rangeHexGen(start, end).sample match {
          case Some(hex) => IO.pure(hex -> value)
          case None      => IO.pure(startHex -> value)
        }
      }
  }

  def longGen: Gen[Long] = Gen.long

  def stringGen: Gen[String] = Gen.alphaNumStr.suchThat(_.nonEmpty)

  def intGen: Gen[Int] = Gen.chooseNum(Int.MinValue, Int.MaxValue)

  case class TestData(id: Long, value: String)

  object TestData {
    implicit val encoder: Encoder[TestData] = io.circe.generic.semiauto.deriveEncoder
  }

  def testDataGen: Gen[TestData] = for {
    id <- longGen
    value <- stringGen
  } yield TestData(id, value)

  def withRandomIndex[A](list: List[A]): Gen[IO[(List[A], Int)]] =
    Gen.choose(0, math.max(0, list.size - 1)).map(idx => IO.pure((list, idx)))

  def subsetsGen[A](list: List[A], minSize: Int = 1): Gen[List[A]] =
    Gen.someOf(list).map(_.toList).suchThat(_.size >= minSize)
}
