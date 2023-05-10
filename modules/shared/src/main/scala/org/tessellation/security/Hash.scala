package org.tessellation.security

import java.nio.charset.StandardCharsets

import org.tessellation.ext.derevo.ordering
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.hash.Hash

import com.google.common.hash.{HashCode, Hashing}
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

    def hashCodeFromBytes(bytes: Array[Byte]): HashCode =
      Hashing.sha256().hashBytes(bytes)

    def fromBytes(bytes: Array[Byte]): Hash =
      Hash(hashCodeFromBytes(bytes).toString)

    def empty: Hash = Hash(s"%064d".format(0))

    implicit val arbitrary: Arbitrary[Hash] = Arbitrary(Gen.stringOfN(64, Gen.hexChar).map(Hash(_)))
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

trait Hashable[F[_]] {
  def hash[A <: AnyRef](data: A): Either[Throwable, Hash]
}

object Hashable {

  def forKryo[F[_]: KryoSerializer]: Hashable[F] = new Hashable[F] {

    def hash[A <: AnyRef](data: A): Either[Throwable, Hash] =
      KryoSerializer[F]
        .serialize(data match {
          case d: Encodable => d.toEncode
          case _            => data
        })
        .map(Hash.fromBytes)
  }
}
