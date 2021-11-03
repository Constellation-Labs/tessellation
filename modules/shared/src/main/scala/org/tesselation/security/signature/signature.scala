package org.tesselation.security.signature

import java.nio.charset.StandardCharsets
import java.security.KeyPair

import cats.Applicative
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import org.tesselation.ext.crypto._
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.ID.Id
import org.tesselation.schema.peer.PeerId
import org.tesselation.security._
import org.tesselation.security.hash.Hash
import org.tesselation.security.hex.Hex

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._
import org.typelevel.log4cats.slf4j.Slf4jLogger

import Signing.{signData, verifySignature}

object signature {

  @derive(decoder, encoder, show, eqv)
  @newtype
  case class Signature(value: Hex)

  @derive(decoder, encoder, show, eqv)
  case class SignatureProof(id: Id, signature: Signature)

  private[security] def signatureProofFromData[F[_]: Async: SecurityProvider: KryoSerializer, A <: AnyRef](
    data: A,
    keyPair: KeyPair
  ): F[SignatureProof] =
    for {
      id <- PeerId._Id.get(PeerId.fromPublic(keyPair.getPublic)).pure[F]
      signature <- data.hashF
        .map(_.value.getBytes)
        .flatMap(signData(_)(keyPair.getPrivate))
        .map(Hex.fromBytes(_))
        .map(Signature(_))
    } yield SignatureProof(id, signature)

  private[security] def verifySignatureProof[F[_]: Async: SecurityProvider](
    hash: Hash,
    signatureProof: SignatureProof
  ): F[Boolean] = {
    val verifyResult = for {
      signatureBytes <- Async[F].delay { signatureProof.signature.coerce.toBytes }
      publicKey <- signatureProof.id.hex.toPublicKey
      result <- verifySignature(hash.value.getBytes(StandardCharsets.UTF_8), signatureBytes)(publicKey)
    } yield result

    verifyResult.handleErrorWith { err =>
      Slf4jLogger.getLogger[F].error(err)(s"Failed to verify signature for peer ${signatureProof.id.show}") >>
        Applicative[F].pure(false)
    }

  }

}
