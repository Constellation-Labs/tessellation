package org.tessellation.schema.security.signature

import java.security.{PrivateKey, PublicKey, Signature}

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.schema.security.{SecureRandom, SecurityProvider}

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
      _ <- Async[F].delay(s.update(bytes))
      signed <- Async[F].delay(s.sign())
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
      _ <- Async[F].delay(s.update(originalInput))
      result <- Async[F].delay(s.verify(signedOutput))
    } yield result
}
