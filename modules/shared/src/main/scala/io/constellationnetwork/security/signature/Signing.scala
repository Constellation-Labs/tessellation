package io.constellationnetwork.security.signature

import java.security.{PrivateKey, PublicKey, Signature}

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.security.{SecureRandom, SecurityProvider}

object Signing {
  val defaultSignFunc = "SHA512withECDSA"

  def signData[F[_]: Async: SecurityProvider](
    bytes: Array[Byte],
    signFunc: String = defaultSignFunc
  )(privateKey: PrivateKey): F[Array[Byte]] =
    for {
      s <- Async[F].delay {
        Signature.getInstance(signFunc, SecurityProvider[F].provider)
      }
      _ <- SecureRandom
        .get[F]
        .flatMap { secureRandom =>
          Async[F].delay(s.initSign(privateKey, secureRandom))
        }
      signed <- Async[F].blocking {
        s.update(bytes)
        s.sign()
      }
    } yield signed

  def verifySignature[F[_]: Async: SecurityProvider](
    originalInput: Array[Byte],
    signedOutput: Array[Byte],
    signFunc: String = defaultSignFunc
  )(publicKey: PublicKey): F[Boolean] =
    for {
      s <- Async[F].delay {
        Signature.getInstance(signFunc, SecurityProvider[F].provider)
      }
      _ <- Async[F].delay(s.initVerify(publicKey))
      result <- Async[F].blocking {
        s.update(originalInput)
        s.verify(signedOutput)
      }
    } yield result
}
