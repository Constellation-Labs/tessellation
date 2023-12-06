package org.tessellation.env

import org.tessellation.ext.decline.decline._

import com.monovore.decline.Opts
import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import enumeratum.EnumEntry._
import enumeratum._

@derive(eqv, encoder, decoder, show)
sealed abstract class AppEnvironment extends EnumEntry with Lowercase

object AppEnvironment extends Enum[AppEnvironment] with CirisEnum[AppEnvironment] {
  case object Dev extends AppEnvironment
  case object Testnet extends AppEnvironment
  case object Integrationnet extends AppEnvironment
  case object Mainnet extends AppEnvironment

  val values = findValues

  val opts: Opts[AppEnvironment] = Opts
    .option[AppEnvironment]("env", help = "Environment", short = "e")
    .orElse(Opts.env[AppEnvironment]("CL_APP_ENV", help = "Environment"))
    .withDefault(AppEnvironment.Testnet)
}
