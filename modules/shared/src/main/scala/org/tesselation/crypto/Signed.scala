package org.tesselation.crypto

import java.security.KeyPair

import cats.effect.Async
import cats.syntax.functor._

import org.tesselation.crypto.signature.{HashSignature, hashSignatureFromData}
import org.tesselation.keytool.security.SecurityProvider
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.ID.Id
import org.tesselation.schema.peer.PeerId

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, eqv, show)
case class Signed[A](value: A, id: Id, hashSignature: HashSignature)

object Signed {

  def forAsyncKryo[F[_]: Async: SecurityProvider: KryoSerializer, A <: AnyRef](
    data: A,
    keyPair: KeyPair
  ): F[Signed[A]] =
    hashSignatureFromData(data, keyPair).map { hs =>
      val id = PeerId._Id.get(PeerId.fromPublic(keyPair.getPublic))
      Signed[A](data, id, hs)
    }
}

@derive(encoder, decoder, eqv, show)
case class SignedHash(id: Id, hashSignature: HashSignature)

object SignedHash {

  def forAsyncKryo[F[_]: Async: SecurityProvider: KryoSerializer, A <: AnyRef](
    data: A,
    keyPair: KeyPair
  ): F[SignedHash] =
    hashSignatureFromData(data, keyPair).map { hs =>
      val id = PeerId._Id.get(PeerId.fromPublic(keyPair.getPublic))
      SignedHash(id, hs)
    }
}
