package io.constellationnetwork.rosetta.domain

import cats.syntax.option._

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import io.constellationnetwork.rosetta.domain.network.{BlockchainId, NetworkEnvironment}

import derevo.cats.{eqv, show}
import derevo.circe.magnolia._
import derevo.derive
import enumeratum.values._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto.autoRefineV
import eu.timepit.refined.cats._
import eu.timepit.refined.generic.Equal
import io.circe.refined._
import io.estatico.newtype.macros.newtype

@derive(customizableDecoder, customizableEncoder, eqv, show)
case class NetworkIdentifier(
  blockchain: BlockchainId,
  network: NetworkEnvironment,
  subNetworkIdentifier: Option[SubNetworkIdentifier]
)
object NetworkIdentifier {
  def fromAppEnvironment(appEnvironment: AppEnvironment): Option[NetworkIdentifier] =
    NetworkEnvironment
      .fromAppEnvironment(appEnvironment)
      .map(NetworkIdentifier(BlockchainId.dag, _, none))
}

@derive(customizableDecoder, customizableEncoder, eqv, show)
case class SubNetworkIdentifier(
  network: String
)

object network {

  @derive(eqv, show)
  sealed abstract class NetworkEnvironment(val value: String) extends StringEnumEntry
  object NetworkEnvironment extends StringEnum[NetworkEnvironment] with StringCirceEnum[NetworkEnvironment] {
    val values = findValues

    case object Testnet extends NetworkEnvironment(value = "testnet")
    case object Integrationnet extends NetworkEnvironment(value = "integrationnet")
    case object Mainnet extends NetworkEnvironment(value = "mainnet")

    def fromAppEnvironment(appEnvironment: AppEnvironment): Option[NetworkEnvironment] =
      appEnvironment match {
        case AppEnvironment.Mainnet        => NetworkEnvironment.Mainnet.some
        case AppEnvironment.Testnet        => NetworkEnvironment.Testnet.some
        case AppEnvironment.Integrationnet => NetworkEnvironment.Integrationnet.some
        case _                             => none[NetworkEnvironment]
      }
  }

  type BlockchainIdRefined = String Refined Equal["dag"]

  @derive(decoder, encoder, eqv, show)
  @newtype
  case class BlockchainId(value: BlockchainIdRefined)
  object BlockchainId {
    val dag = BlockchainId("dag")
  }

}
