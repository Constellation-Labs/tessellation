package org.tesselation.keytool.config

import cats.effect.Async
import cats.syntax.parallel._

import org.tesselation.keytool.config.types.AppConfig

import ciris._

object KeytoolConfig {

  def load[F[_]: Async]: F[AppConfig] = {
    val legacyConfig = (
      env("CL_KEYSTORE"),
      env("CL_STOREPASS").secret,
      env("CL_KEYPASS").secret,
      env("CL_KEYALIAS").secret
    ).parMapN { (keystore, storepass, keypass, keyalias) =>
      AppConfig(keystore = keystore, storepass = storepass, keypass = keypass, keyalias = keyalias)
    }

    val config = (
      env("CL_KEYSTORE"),
      env("CL_PASSWORD").secret,
      env("CL_KEYALIAS").secret
    ).parMapN { (keystore, password, keyalias) =>
      AppConfig(keystore = keystore, storepass = password, keypass = password, keyalias = keyalias)
    }

    config.or(legacyConfig)
  }.load[F]

}
