package org.tessellation.config

import enumeratum.EnumEntry._
import enumeratum._

sealed abstract class AppEnvironment extends EnumEntry with Lowercase

object AppEnvironment extends Enum[AppEnvironment] with CirisEnum[AppEnvironment] {
  case object Testnet extends AppEnvironment
  case object Mainnet extends AppEnvironment

  val values = findValues
}
