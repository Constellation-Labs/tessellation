package org.tesselation.keytool.security

import cats.Applicative
import cats.syntax.flatMap._
import cats.effect.{Async, Resource}
import org.bouncycastle.jce.provider.BouncyCastleProvider

object SecurityProvider {

  private def insertProvider[F[_]: Async](): F[BouncyCastleProvider] = {
    import java.security.Security
    Async[F].delay { new BouncyCastleProvider() }.flatTap { provider =>
      Async[F].delay { Security.insertProviderAt(provider, 1) }
    }
  }

  def make[F[_]: Async]: Resource[F, BouncyCastleProvider] =
    Resource.make(insertProvider())(_ => Applicative[F].unit)
}
