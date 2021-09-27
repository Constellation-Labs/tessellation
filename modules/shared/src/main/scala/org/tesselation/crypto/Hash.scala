package org.tesselation.crypto

import org.tesselation.crypto.hash.Hash
import org.tesselation.kryo.KryoSerializer

import com.google.common.hash.Hashing
import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype

object hash {

  @derive(encoder, decoder, show, eqv)
  @newtype
  case class Hash(value: String)

}

trait Hashable[F[_]] {
  def hash[A <: AnyRef](data: A): Either[Throwable, Hash]
}

object Hashable {

  def forKryo[F[_]: KryoSerializer]: Hashable[F] = new Hashable[F] {

    def sha256(bytes: Array[Byte]): String = Hashing.sha256().hashBytes(bytes).toString

    def hash[A <: AnyRef](data: A): Either[Throwable, Hash] =
      KryoSerializer[F]
        .serialize(data)
        .map(sha256)
        .map(Hash.apply)
  }
}
