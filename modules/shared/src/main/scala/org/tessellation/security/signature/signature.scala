package org.tessellation.security.signature

import java.nio.charset.StandardCharsets
import java.security.{KeyPair, PrivateKey}

import cats.Applicative
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import org.tessellation.ext.cats.data.OrderBasedOrdering
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.hex._
import org.tessellation.schema.id.Id
import org.tessellation.schema.peer.PeerId
import org.tessellation.security._
import org.tessellation.security.hash.Hash

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.refineV
import io.circe.refined._
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._
import org.typelevel.log4cats.slf4j.Slf4jLogger

import Signing.{signData, verifySignature}

object signature {

  @derive(decoder, encoder, show, order)
  @newtype
  case class Signature(value: HexString)

  object Signature {

    def fromHash[F[_]: Async: SecurityProvider](privateKey: PrivateKey, hash: Hash): F[Signature] =
      signData(hash.coerce.value.getBytes(StandardCharsets.UTF_8))(privateKey)
        .flatMap(raw => refineV[HexStringSpec](raw.toHexString.value).leftMap(new RuntimeException(_)).liftTo[F])
        .map(hex => Signature(hex))

  }

  @derive(decoder, encoder, show, order)
  case class SignatureProof(id: Id, signature: Signature)

  object SignatureProof {

    implicit object OrderingInstance extends OrderBasedOrdering[SignatureProof]

    def fromHash[F[_]: Async: SecurityProvider](keyPair: KeyPair, hash: Hash): F[SignatureProof] =
      for {
        id <- PeerId._Id.get(PeerId.fromPublic(keyPair.getPublic)).pure[F]
        signature <- Signature.fromHash(keyPair.getPrivate, hash)
      } yield SignatureProof(id, signature)

    def fromData[F[_]: Async: SecurityProvider: KryoSerializer, A <: AnyRef](
      keyPair: KeyPair
    )(data: A): F[SignatureProof] = data.hashF.flatMap(SignatureProof.fromHash(keyPair, _))

  }

  def verifySignatureProof[F[_]: Async: SecurityProvider](
    hash: Hash,
    signatureProof: SignatureProof
  ): F[Boolean] = {
    val verifyResult = for {
      signatureBytes <- Async[F].delay(signatureProof.signature.coerce.toBytes)
      publicKey <- signatureProof.id.hex.toPublicKey
      result <- verifySignature(hash.coerce.value.getBytes(StandardCharsets.UTF_8), signatureBytes)(publicKey)
    } yield result

    verifyResult.handleErrorWith { err =>
      Slf4jLogger.getLogger[F].error(err)(s"Failed to verify signature for peer ${signatureProof.id.show}") >>
        Applicative[F].pure(false)
    }

  }

}
