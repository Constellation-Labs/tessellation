package org.tessellation.schema.security

import cats.Applicative
import cats.effect.{Async, Resource}

import org.bouncycastle.jce.provider.BouncyCastleProvider

trait SecurityProvider[F[_]] {
  val provider: BouncyCastleProvider
}

object SecurityProvider {
  def apply[F[_]: SecurityProvider]: SecurityProvider[F] = implicitly

  private def make[F[_]: Async]: Resource[F, BouncyCastleProvider] =
    Resource.make {
      Async[F].delay(new BouncyCastleProvider())
    }(_ => Applicative[F].unit)

  def forAsync[F[_]: Async]: Resource[F, SecurityProvider[F]] = make[F].map { bcProvider =>
    new SecurityProvider[F] {
      val provider: BouncyCastleProvider = bcProvider
    }
  }
}
