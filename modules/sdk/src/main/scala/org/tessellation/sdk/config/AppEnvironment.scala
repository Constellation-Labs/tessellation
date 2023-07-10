package org.tessellation.sdk.config

import org.tessellation.ext.decline.decline._

import com.monovore.decline.Opts
import derevo.cats.eqv
import derevo.derive
import enumeratum.EnumEntry._
import enumeratum._

@derive(eqv)
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
