package io.constellationnetwork.env

import cats.effect._

import io.constellationnetwork.security.hash.Hash

import fs2.Stream
import weaver._
import weaver.scalacheck.Checkers

object JarSignatureSuite extends SimpleIOSuite with Checkers {

  test("digest of empty") {

    JarSignature.digestOf[IO](Stream.empty).map { hash =>
      expect.eql(Hash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"), hash)
    }

  }

  test("digest of data") {

    val data = fs2.Stream[IO, Byte](0x01, 0x02, 0x0f, 0x14, 0x09)
    JarSignature.digestOf[IO](data).map { hash =>
      expect.eql(Hash("ea2c1f710fa03eb873135021041c8ebf3038b993e5c3f1fc9b9adb0e7da211ae"), hash)
    }

  }

}
