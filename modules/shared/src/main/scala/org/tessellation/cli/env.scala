package org.tessellation.cli

import org.tessellation.ext.decline.decline._

import ciris.Secret
import com.monovore.decline._
import fs2.io.file.Path
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._

object env {

  @newtype
  case class Password(value: Secret[String])

  object Password {

    val opts: Opts[Password] = Opts
      .env[Password]("CL_PASSWORD", help = "Password")
  }

  @newtype
  case class StorePass(value: Secret[String])

  object StorePass {

    val opts: Opts[StorePass] = Opts
      .env[StorePass]("CL_STOREPASS", help = "Keystore password")
  }

  @newtype
  case class KeyPass(value: Secret[String])

  object KeyPass {

    val opts: Opts[KeyPass] = Opts
      .env[KeyPass]("CL_KEYPASS", help = "Key password")
  }

  @newtype
  case class KeyAlias(value: Secret[String])

  object KeyAlias {

    val opts: Opts[KeyAlias] = Opts
      .env[KeyAlias]("CL_KEYALIAS", help = "Alias of key in keystore")
  }

  @newtype
  case class StorePath(value: Path)

  object StorePath {

    val opts: Opts[StorePath] = Opts
      .env[Path]("CL_KEYSTORE", help = "Keystore path")
      .map(_.coerce)
  }

  @newtype
  case class SeedListPath(value: Path)

  object SeedListPath {
    val opts: Opts[Option[SeedListPath]] = Opts.option[SeedListPath]("seedlist", "").orNone
    val priorityOpts: Opts[Option[SeedListPath]] = Opts.option[SeedListPath]("prioritySeedlist", "").orNone
  }
}
