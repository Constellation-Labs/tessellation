package org.tesselation.keytool.security

import cats.effect.Async

import java.security.{SecureRandom => jSecureRandom}

object SecureRandom {
  val secureRandomInstance = "NativePRNGNonBlocking"

  def get[F[_]: Async]: F[jSecureRandom] =
    Async[F].delay {
      try {
        jSecureRandom.getInstance(secureRandomInstance)
      } catch {
        case _: Throwable => jSecureRandom.getInstanceStrong
      }
    }
}
