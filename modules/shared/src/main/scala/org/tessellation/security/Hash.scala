package org.tessellation.security

import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.hash.Hash

import com.google.common.hash.Hashing
import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype

object hash {

  @derive(encoder, decoder, show, eqv)
  @newtype
  case class Hash(value: String)

  object Hash {

    def fromBytes(bytes: Array[Byte]): Hash =
      Hash(Hashing.sha256().hashBytes(bytes).toString)
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
