package org.tesselation.keytool.security

import java.security.{SecureRandom => jSecureRandom}

import cats.effect.Async

object WithSecureRandom {
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
