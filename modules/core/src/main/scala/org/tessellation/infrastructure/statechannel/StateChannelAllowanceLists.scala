package org.tessellation.infrastructure.statechannel

import cats.data.NonEmptySet
import cats.syntax.option._

import org.tessellation.cli.AppEnvironment
import org.tessellation.cli.AppEnvironment._
import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId

object StateChannelAllowanceLists {

  def get(env: AppEnvironment): Option[Map[Address, NonEmptySet[PeerId]]] =
    env match {
      case Dev | Testnet | Integrationnet => none
      case Mainnet                        => Map.empty[Address, NonEmptySet[PeerId]].some
    }

}
