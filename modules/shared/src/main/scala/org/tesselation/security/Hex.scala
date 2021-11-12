package org.tesselation.security

import java.nio.charset.Charset
import java.security.spec.X509EncodedKeySpec
import java.security.{KeyFactory, PublicKey}

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tesselation.security.key.{ECDSA, PublicKeyHexPrefix}

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia._
import derevo.derive
import derevo.scalacheck.arbitrary
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._

object hex {

  @derive(arbitrary, decoder, encoder, eqv, show, order, keyEncoder, keyDecoder)
  @newtype
  case class Hex(value: String) {

    def toBytes: Array[Byte] =
      if (value.contains(" ")) {
        value.split(" ").map(Integer.parseInt(_, 16).toByte)
      } else if (value.contains("-")) {
        value.split("-").map(Integer.parseInt(_, 16).toByte)
      } else {
        value.sliding(2, 2).toArray.map(Integer.parseInt(_, 16).toByte)
      }

    def toBytes(charset: Charset): Array[Byte] =
      value.getBytes(charset)

    def toPublicKey[F[_]: Async: SecurityProvider]: F[PublicKey] =
      for {
        _ <- Async[F].unit
        prefixed = (PublicKeyHexPrefix + value).coerce[Hex]
        encodedBytes = prefixed.toBytes
        spec <- Async[F].delay {
          new X509EncodedKeySpec(encodedBytes)
        }
        kf <- Async[F].delay {
          KeyFactory.getInstance(ECDSA, SecurityProvider[F].provider)
        }
        pk <- Async[F].delay {
          kf.generatePublic(spec)
        }
      } yield pk
  }

  object Hex {

    def fromBytes(bytes: Array[Byte], sep: Option[String] = None): Hex =
      sep match {
        case None => bytes.map("%02x".format(_)).mkString.coerce
        case _    => bytes.map("%02x".format(_)).mkString(sep.get).coerce
      }
  }

}
