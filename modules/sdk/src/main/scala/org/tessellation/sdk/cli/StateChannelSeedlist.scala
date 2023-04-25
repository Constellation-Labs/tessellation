package org.tessellation.sdk.cli

import org.tessellation.schema.address.Address
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.AppEnvironment._

object StateChannelSeedlist {

  def get(env: AppEnvironment): Option[Set[Address]] =
    env match {
      case Dev     => None
      case Testnet => Some(Set.empty)
      case Mainnet => Some(Set.empty)
    }
}
