package org.tessellation.rosetta.domain

import cats.syntax.option._

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.tessellation.rosetta.domain.network.{BlockchainId, NetworkEnvironment}
import org.tessellation.sdk.config.AppEnvironment

import derevo.cats.{eqv, show}
import derevo.circe.magnolia._
import derevo.derive
import enumeratum.values.{StringEnumEntry, _}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.generic.Equal
import io.circe.refined._
import io.estatico.newtype.macros.newtype

@derive(customizableDecoder, customizableEncoder)
case class NetworkIdentifier(
  blockchain: BlockchainId,
  network: NetworkEnvironment,
  subNetworkIdentifier: Option[SubNetworkIdentifier]
)

@derive(customizableDecoder, customizableEncoder)
case class SubNetworkIdentifier(
  network: String
)

object network {

  @derive(eqv, show)
  sealed abstract class NetworkEnvironment(val value: String) extends StringEnumEntry
  object NetworkEnvironment extends StringEnum[NetworkEnvironment] with StringCirceEnum[NetworkEnvironment] {
    val values = findValues

    case object Testnet extends NetworkEnvironment(value = "testnet")
    case object Mainnet extends NetworkEnvironment(value = "mainnet")

    def fromAppEnvironment(appEnvironment: AppEnvironment): Option[NetworkEnvironment] =
      appEnvironment match {
        case AppEnvironment.Mainnet => NetworkEnvironment.Mainnet.some
        case AppEnvironment.Testnet => NetworkEnvironment.Testnet.some
        case _                      => none[NetworkEnvironment]
      }
  }

  type BlockchainIdRefined = String Refined Equal["dag"]

  @derive(decoder, encoder)
  @newtype
  case class BlockchainId(value: BlockchainIdRefined)

}
