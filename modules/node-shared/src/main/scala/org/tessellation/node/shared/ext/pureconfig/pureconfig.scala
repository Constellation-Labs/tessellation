package org.tessellation.node.shared.ext

import java.nio.file.{Path => JPath}

import cats.data.NonEmptySet

import org.tessellation.env.AppEnvironment
import org.tessellation.node.shared.domain.statechannel.FeeCalculatorConfig
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.TransactionFee
import org.tessellation.security.hex.Hex

import _root_.pureconfig.ConfigReader
import _root_.pureconfig.ConvertHelpers.catchReadError
import _root_.pureconfig.configurable.genericMapReader
import _root_.pureconfig.generic.auto._
import _root_.pureconfig.module.cats.nonEmptySetReader
import _root_.pureconfig.module.enumeratum._
import eu.timepit.refined.auto._
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
  implicit val environmentToOrdinalMapReader: ConfigReader[Map[AppEnvironment, SnapshotOrdinal]] =
    genericMapReader[AppEnvironment, SnapshotOrdinal](catchReadError(AppEnvironment.withName))
  implicit val environmentToSetOfPeersReader: ConfigReader[Map[AppEnvironment, NonEmptySet[PeerId]]] =
    genericMapReader(catchReadError(AppEnvironment.withName))
  implicit val ordinalToFeeCalculatorConfigReader: ConfigReader[Map[SnapshotOrdinal, FeeCalculatorConfig]] =
    genericMapReader(catchReadError(strOrdinal => SnapshotOrdinal.unsafeApply(strOrdinal.toLong)))
  implicit val envToOrdinalToFeeCalculatorConfigReader: ConfigReader[Map[AppEnvironment, Map[SnapshotOrdinal, FeeCalculatorConfig]]] =
    genericMapReader(catchReadError(AppEnvironment.withName))
}
