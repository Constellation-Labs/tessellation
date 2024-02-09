package org.tessellation.security

import java.nio.charset.StandardCharsets

import cats.Show

import org.tessellation.ext.derevo.ordering

import com.google.common.hash.{HashCode, Hashing}
import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
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

    def hashCodeFromBytes(bytes: Array[Byte]): HashCode =
      Hashing.sha256().hashBytes(bytes)

    def fromBytes(bytes: Array[Byte]): Hash =
      Hash(hashCodeFromBytes(bytes).toString)

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
