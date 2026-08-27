package io.constellationnetwork.node.shared.ext

import java.nio.file.{Path => JPath}

import cats.data.NonEmptySet

import scala.collection.immutable.SortedMap

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.ext.http4s.AddressVar
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculatorConfig
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.priceOracle.TokenPair
import io.constellationnetwork.schema.priceOracle.TokenPair.DAG_USD
import io.constellationnetwork.schema.transaction.TransactionFee
import io.constellationnetwork.schema.{NonNegFraction, SnapshotOrdinal}
import io.constellationnetwork.security.hex.Hex

import _root_.pureconfig.ConvertHelpers.catchReadError
import _root_.pureconfig.configurable.genericMapReader
import _root_.pureconfig.generic.auto._
import _root_.pureconfig.module.cats.nonEmptySetReader
import _root_.pureconfig.{ConfigReader, ConfigWriter}
import eu.timepit.refined.pureconfig._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt, PosLong}
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
  implicit val envToRouteRateLimiterConfigReader: ConfigReader[Map[AppEnvironment, RouteRateLimiterConfig]] =
    genericMapReader(catchReadError(AppEnvironment.withName))

  implicit val addressReader: ConfigReader[Address] = ConfigReader[String].map(AddressVar.unapply).map(_.get)
  implicit val tokenPairToBigdecimalReader: ConfigReader[Map[TokenPair, BigDecimal]] = genericMapReader(catchReadError {
    case "DAG::USD" => DAG_USD
  })
  implicit val envToPriceOracleConfigReader: ConfigReader[Map[AppEnvironment, PriceOracleConfig]] =
    genericMapReader(catchReadError(AppEnvironment.withName))
  implicit val envToPosIntMapReader: ConfigReader[Map[AppEnvironment, PosInt]] =
    genericMapReader(catchReadError(AppEnvironment.withName))
  implicit val envToIntMapReader: ConfigReader[Map[AppEnvironment, Int]] =
    genericMapReader(catchReadError(AppEnvironment.withName))
  // Ordinal-keyed dust sweeps (env -> ordinal -> sweep), mirroring the fee-config readers above. DustSweep derives from the
  // Balance and Address readers; the inner map is built as a SortedMap (the consumer's type) via the same SortedMap.from idiom
  // the codebase uses to resolve feeConfigs.
  implicit val ordinalToDustSweepReader: ConfigReader[SortedMap[SnapshotOrdinal, DustSweep]] =
    genericMapReader[SnapshotOrdinal, DustSweep](catchReadError(strOrdinal => SnapshotOrdinal.unsafeApply(strOrdinal.toLong)))
      .map(SortedMap.from(_))
  implicit val envToOrdinalToDustSweepReader: ConfigReader[Map[AppEnvironment, SortedMap[SnapshotOrdinal, DustSweep]]] =
    genericMapReader(catchReadError(AppEnvironment.withName))
  implicit val fieldsAddedOrdinalsReader: ConfigReader[FieldsAddedOrdinals] =
    ConfigReader.forProduct19(
      "tessellation-3-migration",
      "tessellation-301-migration",
      "check-sync-global-snapshot-field",
      "metagraph-sync-data",
      "updated-last-sync-global-order",
      "updated-last-sync-global-from-peers-in-consensus",
      "updating-combine-function-spend-actions",
      "fixing-allow-spend-expiration",
      "fixing-allow-spend-and-token-lock-validation",
      "set-sum-fix",
      "sc-fee-balance-from-context",
      "sub-trie-roots",
      "delegated-rewards-full-committee",
      "fee-transaction-security",
      "dust-sweeps",
      "fixing-data-application-fee-validation",
      "fixing-allow-spend-destination-credit",
      "preventing-allow-spend-resurrection",
      "fixing-global-allow-spend-expiration"
    )(FieldsAddedOrdinals.apply)
}
