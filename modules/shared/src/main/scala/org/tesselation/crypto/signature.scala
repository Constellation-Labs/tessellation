package org.tesselation.crypto

import java.security.KeyPair

import cats.effect.Async
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tesselation.ext.crypto._
import org.tesselation.keytool.security.Signing.signData
import org.tesselation.keytool.security.{SecurityProvider, bytes2hex}
import org.tesselation.kryo.KryoSerializer

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype

object signature {

  @derive(decoder, encoder, show, eqv)
  @newtype
  case class HashSignature(value: String)

  private[crypto] def hashSignatureFromData[F[_]: Async: SecurityProvider: KryoSerializer, A <: AnyRef](
    data: A,
    keyPair: KeyPair
  ): F[HashSignature] =
    data.hash
      .liftTo[F]
      .map(_.value.getBytes)
      .flatMap(signData(_)(keyPair.getPrivate))
      .map(bytes2hex(_))
      .map(HashSignature.apply)

}
