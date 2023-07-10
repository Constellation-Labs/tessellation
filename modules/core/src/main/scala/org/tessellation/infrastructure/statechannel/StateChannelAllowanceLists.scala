package org.tessellation.infrastructure.statechannel

import cats.data.NonEmptySet
import cats.syntax.option._

import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.AppEnvironment._

object StateChannelAllowanceLists {

  def get(env: AppEnvironment): Option[Map[Address, NonEmptySet[PeerId]]] =
    env match {
      case Dev | Testnet | Integrationnet => none
      case Mainnet                        => Map.empty[Address, NonEmptySet[PeerId]].some
    }

}
