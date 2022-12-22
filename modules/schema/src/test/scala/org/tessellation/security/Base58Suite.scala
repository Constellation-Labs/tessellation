package org.tessellation.schema.security

import cats.effect.IO
import cats.syntax.applicative._

import org.tessellation.generators.nonEmptyStringGen
import org.tessellation.schema.security.generators.base58StringGen

import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object Base58Suite extends SimpleIOSuite with Checkers {

  test("round trip one way") {
    val inputGen = base58StringGen

    forall(inputGen) { input =>
      expect.same(input, Base58.encode(Base58.decode(input).toIndexedSeq))
    }
  }

  test("round trip other way") {
    val inputGen = nonEmptyStringGen

    forall(inputGen) { input =>
      expect.same(input, new String(Base58.decode(Base58.encode(input.map(_.toByte)))))
    }
  }

  test("encodes samples correctly") {
    val sample = Map(
      "Hello World!" -> "2NEpo7TZRRrLZSi2U",
      "The quick brown fox jumps over the lazy dog." -> "USm3fpXnKG5EUBx2ndxBDMPVciP5hGey2Jh4NDv6gmeo1LkMeiKrLJUUBk6Z"
    )

    forEach(sample.toList) {
      case (plain, encoded) =>
        expect.same(encoded, Base58.encode(plain.map(_.toByte)))
    }.pure[IO]
  }
}
