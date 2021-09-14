package org.tesselation.keytool.config

import cats.effect.Async
import cats.syntax.parallel._

import org.tesselation.keytool.config.types.AppConfig

import ciris._

object KeytoolConfig {

  def load[F[_]: Async]: F[AppConfig] =
    (
      env("CL_KEYSTORE"),
      env("CL_STOREPASS").secret,
      env("CL_KEYPASS").secret,
      env("CL_KEYALIAS").secret
    ).parMapN { (keystore, storepass, keypass, keyalias) =>
      AppConfig(
        keystore = keystore,
        storepass = storepass,
        keypass = keypass,
        keyalias = keyalias
      )
    }.load[F]

}
