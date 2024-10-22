package io.constellationnetwork.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import cats.Show

import io.constellationnetwork.ext.derevo.ordering

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._
import org.scalacheck.{Arbitrary, Gen}

object hash {

  @derive(encoder, decoder, ordering, order, show)
  @newtype
  case class Hash(value: String) {
    def getBytes: Array[Byte] = value.getBytes(StandardCharsets.UTF_8)
  }

  object Hash {

    def sha256FromBytes(bytes: Array[Byte]): Array[Byte] = {
      val md = MessageDigest.getInstance("SHA-256")
      md.update(bytes)
      md.digest()
    }

    def fromBytes(bytes: Array[Byte]): Hash = {
      val sha256Bytes = sha256FromBytes(bytes)
      val sha256String = sha256Bytes
        .foldLeft(new StringBuilder(64))((acc, x) => acc.append(s"%02x".format(x)))
        .toString
      Hash(sha256String)
    }

    def empty: Hash = Hash(s"%064d".format(0))

    implicit val arbitrary: Arbitrary[Hash] = Arbitrary(Gen.stringOfN(64, Gen.hexChar).map(Hash(_)))

    val shortShow: Show[Hash] = Show.show[Hash](h => s"Hash(${h.value.take(8)})")
  }

  @derive(encoder, decoder, ordering, order, show)
  @newtype
  case class ProofsHash(value: String)

  object ProofsHash {
    implicit val arbitrary: Arbitrary[ProofsHash] = Arbitrary(
      Arbitrary.arbitrary[Hash].map(h => ProofsHash(h.coerce[String]))
    )
  }

}
