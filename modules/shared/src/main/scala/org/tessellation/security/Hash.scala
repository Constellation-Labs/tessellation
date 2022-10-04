package org.tessellation.security

import org.tessellation.ext.derevo.ordering
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.hash.Hash

import com.google.common.hash.Hashing
import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._
import io.getquill.MappedEncoding
import org.scalacheck.{Arbitrary, Gen}

object hash {

  @derive(encoder, decoder, ordering, order, show)
  @newtype
  case class Hash(value: String)

  object Hash {

    def fromBytes(bytes: Array[Byte]): Hash =
      Hash(Hashing.sha256().hashBytes(bytes).toString)

    def empty: Hash = Hash(s"%064d".format(0))

    implicit val arbitrary: Arbitrary[Hash] = Arbitrary(Gen.resize(64, Gen.hexStr).map(Hash(_)))

    implicit val quillEncode: MappedEncoding[Hash, String] =
      MappedEncoding[Hash, String](_.value)

    implicit val quillDecode: MappedEncoding[String, Hash] =
      MappedEncoding[String, Hash](
        Hash(_)
      )
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
