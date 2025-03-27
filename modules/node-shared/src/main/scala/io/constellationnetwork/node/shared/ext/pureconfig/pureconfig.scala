package io.constellationnetwork.node.shared.ext

import java.nio.file.{Path => JPath}

import cats.data.NonEmptySet

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.ext.http4s.AddressVar
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculatorConfig
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.transaction.TransactionFee
import io.constellationnetwork.security.hex.Hex

import _root_.pureconfig.ConfigReader
import _root_.pureconfig.ConvertHelpers.catchReadError
import _root_.pureconfig.configurable.genericMapReader
import _root_.pureconfig.generic.auto._
import _root_.pureconfig.module.cats.nonEmptySetReader
import eu.timepit.refined.pureconfig._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.Path

package object pureconfig {
  implicit val pathReader: ConfigReader[Path] = ConfigReader[JPath].map(Path.fromNioPath)
  implicit val amountReader: ConfigReader[Amount] = ConfigReader[NonNegLong].map(Amount(_))
  implicit val balanceReader: ConfigReader[Balance] = ConfigReader[NonNegLong].map(Balance(_))
  implicit val transactionFeeReader: ConfigReader[TransactionFee] = ConfigReader[NonNegLong].map(TransactionFee(_))
  implicit val peerIdReader: ConfigReader[PeerId] = ConfigReader[String].map(Hex(_)).map(PeerId(_))
  implicit val ordinalReader: ConfigReader[SnapshotOrdinal] = ConfigReader[NonNegLong].map(SnapshotOrdinal(_))
  implicit val epochProgressReader: ConfigReader[EpochProgress] = ConfigReader[NonNegLong].map(EpochProgress(_))
  implicit val environmentToOrdinalMapReader: ConfigReader[Map[AppEnvironment, SnapshotOrdinal]] =
    genericMapReader[AppEnvironment, SnapshotOrdinal](catchReadError(AppEnvironment.withName))
  implicit val environmentToEpochProgressMapReader: ConfigReader[Map[AppEnvironment, EpochProgress]] =
    genericMapReader[AppEnvironment, EpochProgress](catchReadError(AppEnvironment.withName))
  implicit val environmentToSetOfPeersReader: ConfigReader[Map[AppEnvironment, NonEmptySet[PeerId]]] =
    genericMapReader(catchReadError(AppEnvironment.withName))
  implicit val ordinalToFeeCalculatorConfigReader: ConfigReader[Map[SnapshotOrdinal, FeeCalculatorConfig]] =
    genericMapReader(catchReadError(strOrdinal => SnapshotOrdinal.unsafeApply(strOrdinal.toLong)))
  implicit val envToOrdinalToFeeCalculatorConfigReader: ConfigReader[Map[AppEnvironment, Map[SnapshotOrdinal, FeeCalculatorConfig]]] =
    genericMapReader(catchReadError(AppEnvironment.withName))

  implicit val addressReader: ConfigReader[Address] = ConfigReader[String].map(AddressVar.unapply).map(_.get)
}
