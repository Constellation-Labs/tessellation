package org.tessellation.cli

import cats.syntax.contravariantSemigroupal._

import org.tessellation.config.types.DBConfig
import org.tessellation.ext.decline.decline._

import ciris.Secret
import com.monovore.decline._
import com.monovore.decline.refined._
import eu.timepit.refined.types.string.NonEmptyString

object db {

  val driverOpts: Opts[NonEmptyString] = Opts
    .env[NonEmptyString]("CL_DB_DRIVER", help = "Database driver class")
    .withDefault(NonEmptyString.unsafeFrom("org.h2.Driver"))

  val urlOpts: Opts[NonEmptyString] = Opts
    .env[NonEmptyString]("CL_DB_URL", help = "Database URL")
    .withDefault(NonEmptyString.unsafeFrom("jdbc:h2:mem:nodedb"))

  val userOpts: Opts[NonEmptyString] = Opts
    .env[NonEmptyString]("CL_DB_USER", help = "Database user")
    .withDefault(NonEmptyString.unsafeFrom("sa"))

  val passwordOpts: Opts[Secret[String]] = Opts
    .env[Secret[String]]("CL_DB_PASSWORD", help = "Database password")
    .withDefault(Secret(""))

  val opts = (driverOpts, urlOpts, userOpts, passwordOpts).mapN(DBConfig.apply(_, _, _, _))
}
