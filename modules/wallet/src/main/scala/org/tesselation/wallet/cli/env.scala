package org.tesselation.wallet.cli

import cats.syntax.contravariantSemigroupal._

import org.tesselation.ext.decline.decline._

import ciris.Secret
import com.monovore.decline._
import fs2.io.file.Path
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._

object env {

  @newtype
  case class StorePass(value: Secret[String])

  @newtype
  case class KeyPass(value: Secret[String])

  @newtype
  case class KeyAlias(value: Secret[String])

  @newtype
  case class Password(value: Secret[String])

  case class EnvConfig(
    keystore: Path,
    storepass: StorePass,
    keypass: KeyPass,
    keyalias: KeyAlias
  )

  private val passwordOpts: Opts[Password] = Opts
    .env[Password]("CL_PASSWORD", help = "Password")

  private val keystoreOpts: Opts[Path] = Opts
    .env[Path]("CL_KEYSTORE", help = "Keystore path")

  private val storepassOpts: Opts[StorePass] = Opts
    .env[StorePass]("CL_STOREPASS", help = "Keystore password")
    .orElse(passwordOpts.map(p => StorePass(p.coerce)))

  private val keypassOots: Opts[KeyPass] = Opts
    .env[KeyPass]("CL_KEYPASS", help = "Key password")
    .orElse(passwordOpts.map(p => KeyPass(p.coerce)))

  private val aliasOpts: Opts[KeyAlias] = Opts
    .env[KeyAlias]("CL_KEYALIAS", help = "Alias of key in keystore")

  val opts = (
    keystoreOpts,
    storepassOpts,
    keypassOots,
    aliasOpts
  ).mapN(EnvConfig)
}
