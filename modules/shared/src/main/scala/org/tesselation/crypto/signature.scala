package org.tesselation.crypto

import java.nio.charset.StandardCharsets
import java.security.KeyPair

import cats.Applicative
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import org.tesselation.crypto.hash.Hash
import org.tesselation.ext.crypto._
import org.tesselation.keytool.security.Signing.{signData, verifySignature}
import org.tesselation.keytool.security._
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.ID._

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype
import org.typelevel.log4cats.slf4j.Slf4jLogger

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

  private[crypto] def verifyHashSignature[F[_]: Async: SecurityProvider: KryoSerializer, A <: AnyRef](
    hash: Hash,
    hashSignature: HashSignature,
    id: Id
  ): F[Boolean] = {
    val verifyResult = for {
      signatureBytes <- Async[F].delay { hex2bytes(hashSignature.value) }
      publicKey <- hexToPublicKey(id.hex)
      result <- verifySignature(hash.value.getBytes(StandardCharsets.UTF_8), signatureBytes)(publicKey)
    } yield result

    verifyResult.handleErrorWith { err =>
      Slf4jLogger.getLogger[F].error(err)(s"Failed to verify signature for peer ${id.show}") >>
        Applicative[F].pure(false)
    }

  }

}
