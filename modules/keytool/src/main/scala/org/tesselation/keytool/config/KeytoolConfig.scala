package org.tesselation.keytool.config

import cats.effect.Async
import cats.syntax.parallel._

import org.tesselation.keytool.config.types.AppConfig

import ciris._

object KeytoolConfig {

  def load[F[_]: Async]: F[AppConfig] =
    (
      env("CL_STOREPASS").secret,
      env("CL_KEYPASS").secret
    ).parMapN { (storepass, keypass) =>
      AppConfig(
        storepass = storepass,
        keypass = keypass
      )
    }.load[F]

}
