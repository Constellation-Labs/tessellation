package io.constellationnetwork.storage

import cats.syntax.eq._

import io.constellationnetwork.storage.PathGenerator.{ChunkSize, Depth, PrefixSize}

import eu.timepit.refined.auto._
import weaver._
import weaver.scalacheck.Checkers

object PathGeneratorSuite extends SimpleIOSuite with Checkers {

  pureTest("flat implementation returns just the filename") {
    val filename = "abcdef.scala"

    val result = PathGenerator.flat.get(filename)

    expect(result === filename)
  }

  pureTest("forHash implementation nests the hash filename by depth and prefix size") {
    val data = List(
      (2, 3, "abcdefghijkl", "abc/def/"),
      (3, 2, "zxcvbnmasdfghj", "zx/cv/bn/"),
      (2, 4, "qazwsxedcrfvtg", "qazw/sxed/")
    )

    forEach(data) {
      case (depth, prefixSize, filename, expectedPrefix) =>
        expect(PathGenerator.forHash(Depth(depth), PrefixSize(prefixSize)).get(filename) === expectedPrefix + filename)
    }
  }

  pureTest("forOrdinal implementation groups the ordinal filename by chunks") {
    val data = List(
      (500, "56", "0/"),
      (500, "550", "500/"),
      (10000, "9999", "0/"),
      (10000, "20001", "20000/")
    )

    forEach(data) {
      case (chunkSize, filename, expectedPrefix) =>
        expect(PathGenerator.forOrdinal(ChunkSize(chunkSize)).get(filename) === expectedPrefix + filename)
    }
  }
}
