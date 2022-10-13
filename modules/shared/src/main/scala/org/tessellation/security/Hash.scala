package org.tessellation.security

import cats.Order._

import org.tessellation.ext.derevo.ordering
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.hex._
import org.tessellation.security.hash.Hash

import com.google.common.hash.Hashing
import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import derevo.scalacheck.arbitrary
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.refineV
import io.circe.refined._
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._
import org.scalacheck.Arbitrary

object hash {

  @derive(arbitrary, encoder, decoder, ordering, order, show)
  @newtype
  case class Hash(value: HexString64)

  object Hash {

    def fromBytes(bytes: Array[Byte]): Hash =
      Hash(refineV[HexString64Spec].unsafeFrom(Hashing.sha256().hashBytes(bytes).asBytes().toHexString))

    def empty: Hash = Hash("0000000000000000000000000000000000000000000000000000000000000000")

  }

  @derive(encoder, decoder, ordering, order, show)
  @newtype
  case class ProofsHash(value: HexString64)

  object ProofsHash {
    implicit val arbitrary: Arbitrary[ProofsHash] = Arbitrary(
      Arbitrary.arbitrary[Hash].map(h => ProofsHash(h.coerce))
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
