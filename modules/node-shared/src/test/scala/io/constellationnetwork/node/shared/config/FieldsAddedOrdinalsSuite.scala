package io.constellationnetwork.node.shared.config

import cats.effect.IO
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types.{DustSweep, FieldsAddedOrdinals}
import io.constellationnetwork.node.shared.ext.pureconfig._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.balance.Balance

import eu.timepit.refined.auto._
import pureconfig.ConfigSource
import weaver.SimpleIOSuite

object FieldsAddedOrdinalsSuite extends SimpleIOSuite {

  private val disabledFieldsAddedOrdinals = FieldsAddedOrdinals(
    tessellation3Migration = Map.empty,
    tessellation301Migration = Map.empty,
    checkSyncGlobalSnapshotField = Map.empty,
    metagraphSyncData = Map.empty,
    updatedLastSyncGlobalOrder = Map.empty,
    updatedLastSyncGlobalFromPeersInConsensus = Map.empty,
    updatingCombineFunctionSpendActions = Map.empty,
    fixingAllowSpendExpiration = Map.empty,
    fixingAllowSpendAndTokenLockValidation = Map.empty
  )

  // Independent expected values: changing a signed-history boundary requires review of this table,
  // not regenerating it from application.conf. Absence is intentional only where recorded here.
  private def allEnvironments(mainnet: Long, testnet: Long, integrationnet: Long): Map[AppEnvironment, SnapshotOrdinal] =
    Map(
      AppEnvironment.Mainnet -> SnapshotOrdinal.unsafeApply(mainnet),
      AppEnvironment.Testnet -> SnapshotOrdinal.unsafeApply(testnet),
      AppEnvironment.Integrationnet -> SnapshotOrdinal.unsafeApply(integrationnet),
      AppEnvironment.Dev -> SnapshotOrdinal.MinValue
    )

  private val expectedThresholds: Map[String, Map[AppEnvironment, SnapshotOrdinal]] = Map(
    "tessellation3Migration" -> allEnvironments(4409045L, 0L, 0L),
    "tessellation301Migration" -> allEnvironments(4915254L, 0L, 0L),
    "checkSyncGlobalSnapshotField" -> allEnvironments(4488000L, 0L, 0L),
    "metagraphSyncData" -> allEnvironments(4915254L, 0L, 0L),
    "updatedLastSyncGlobalOrder" -> allEnvironments(4915254L, 0L, 0L),
    "updatedLastSyncGlobalFromPeersInConsensus" -> allEnvironments(4915254L, 0L, 0L),
    "updatingCombineFunctionSpendActions" -> allEnvironments(4957662L, 0L, 0L),
    "fixingAllowSpendExpiration" -> allEnvironments(5033174L, 0L, 0L),
    "fixingAllowSpendAndTokenLockValidation" -> allEnvironments(5058096L, 0L, 0L),
    "fixingFeeTransactionBalanceOverflow" -> allEnvironments(6814499L, 0L, 0L),
    "fixingDataApplicationFeeValidation" -> allEnvironments(6818000L, 0L, 0L),
    "fixingAllowSpendDestinationCredit" -> allEnvironments(6818000L, 0L, 0L),
    "preventingAllowSpendResurrection" -> allEnvironments(6828500L, 0L, 0L),
    "fixingGlobalAllowSpendExpiration" -> allEnvironments(6828500L, 0L, 0L),
    "fixingSpendActionAggregateBalance" -> allEnvironments(9999999L, 0L, 0L),
    "removingProcessedDelegatedStakeWithdrawals" -> allEnvironments(6176655L, 0L, 0L),
    "tessellation41Migration" -> allEnvironments(9999999L, 0L, 0L)
  )

  test("pins every packaged threshold and intentional absence in every environment") {
    IO {
      val fields = ConfigSource.resources("application.conf").at("fields-added-ordinals").loadOrThrow[FieldsAddedOrdinals]
      // Product names deliberately make a newly added gate fail until the independent table covers it.
      val actual = fields.productElementNames.zip(fields.productIterator).filterNot(_._1 == "dustSweeps").toMap
      expect.same(expectedThresholds, actual) &&
      AppEnvironment.values.foldLeft(success) { (result, environment) =>
        result && expect.same(
          SortedMap.from(expectedThresholds.map { case (name, values) => name -> values.getOrElse(environment, SnapshotOrdinal.MaxValue) }),
          fields.resolvedThresholdsFor(environment)
        )
      }
    }
  }

  test("pins the separate historical hash boundary and complete dust schedule") {
    IO {
      val source = ConfigSource.resources("application.conf")
      val fields = source.at("fields-added-ordinals").loadOrThrow[FieldsAddedOrdinals]
      expect.same(
        allEnvironments(2572384L, 0L, 0L),
        source.at("last-kryo-hash-ordinal").loadOrThrow[Map[AppEnvironment, SnapshotOrdinal]]
      ) &&
      expect(fields.dustSweeps.isEmpty, s"no network schedules a dust sweep: ${fields.dustSweeps}")
    }
  }

  test("the legacy state-proof and incremental staking boundaries are derived from the v4.1 cutover for every environment") {
    IO {
      val fields = ConfigSource.resources("application.conf").at("fields-added-ordinals").loadOrThrow[FieldsAddedOrdinals]
      // mainnet stays on v3.5 proofs/staking until the cutover placeholder is replaced; v4.1-born networks start at 0.
      val expected = Map[AppEnvironment, Long](
        AppEnvironment.Mainnet -> 9999998L,
        AppEnvironment.Testnet -> 0L,
        AppEnvironment.Integrationnet -> 0L,
        AppEnvironment.Dev -> 0L
      )
      AppEnvironment.values.toList.foldMap { environment =>
        val derived = fields.tessellation41LastLegacyOrdinalFor(environment)
        expect(
          derived == SnapshotOrdinal.unsafeApply(expected(environment)),
          s"${environment.entryName}: last legacy ordinal $derived, expected ${expected(environment)}"
        )
      }
    }
  }

  pureTest("the derived boundary is C - 1, never underflows at C = 0, and stays legacy when the cutover is absent") {
    def at(cutover: Option[Long]) =
      disabledFieldsAddedOrdinals
        .copy(tessellation41Migration =
          cutover.map(c => Map[AppEnvironment, SnapshotOrdinal](AppEnvironment.Dev -> SnapshotOrdinal.unsafeApply(c))).getOrElse(Map.empty)
        )
        .tessellation41LastLegacyOrdinalFor(AppEnvironment.Dev)

    expect(at(Some(0L)) == SnapshotOrdinal.MinValue, s"C = 0: ${at(Some(0L))}")
      .and(expect(at(Some(1L)) == SnapshotOrdinal.MinValue, s"C = 1: ${at(Some(1L))}"))
      .and(expect(at(Some(7100001L)) == SnapshotOrdinal.unsafeApply(7100000L), s"C = R + 1: ${at(Some(7100001L))}"))
      .and(
        expect(
          at(None) == SnapshotOrdinal.unsafeApply(SnapshotOrdinal.MaxValue.value.value - 1L),
          s"absent cutover: ${at(None)}"
        )
      )
  }

  test("every folded v4.1 gate resolves to the cutover ordinal in every environment") {
    IO {
      val fields = ConfigSource.resources("application.conf").at("fields-added-ordinals").loadOrThrow[FieldsAddedOrdinals]
      AppEnvironment.values.toList.foldMap { environment =>
        val cutover = fields.tessellation41MigrationFor(environment)
        val folded = List(
          "subTrieRoots" -> fields.subTrieRootsFor(environment),
          "feeTransactionSecurity" -> fields.feeTransactionSecurityFor(environment),
          "currencySnapshotProtocolV1" -> fields.currencySnapshotProtocolV1For(environment),
          "fixingDelegatedStakeDoubleWithdrawal" -> fields.fixingDelegatedStakeDoubleWithdrawalFor(environment)
        )
        folded.foldMap {
          case (name, ordinal) => expect(ordinal == cutover, s"${environment.entryName} $name: $ordinal != cutover $cutover")
        }
      }
    }
  }

  // testnet and integrationnet are fresh-genesised on v4.1: no rule may wait for an ordinal there, and none may be
  // left absent (absent = disabled forever). Mainnet values are v3.5 history and are pinned in expectedThresholds.
  test("every non-mainnet threshold, the Kryo boundary and the fee schedule start at genesis") {
    IO {
      val source = ConfigSource.resources("application.conf")
      val fields = source.at("fields-added-ordinals").loadOrThrow[FieldsAddedOrdinals]
      val kryo = source.at("last-kryo-hash-ordinal").loadOrThrow[Map[AppEnvironment, SnapshotOrdinal]]
      val nonMainnet = AppEnvironment.values.toList.filterNot(_ == AppEnvironment.Mainnet)
      nonMainnet.foldMap { environment =>
        val late = fields.resolvedThresholdsFor(environment).filterNot { case (_, ordinal) => ordinal == SnapshotOrdinal.MinValue }
        expect(late.isEmpty, s"${environment.entryName} thresholds not at genesis: $late")
          .and(expect(kryo.get(environment).contains(SnapshotOrdinal.MinValue), s"${environment.entryName} last-kryo-hash-ordinal: $kryo"))
          .and(
            expect(
              source
                .config()
                .exists(_.getConfig(s"fee-configs.${environment.entryName}").root().keySet().toArray.toList == List("0")),
              s"${environment.entryName} fee-configs must be keyed at ordinal 0"
            )
          )
      }
    }
  }

  // The cutover C must sit above every other activation: on mainnet R + 1 lands after all v3.5 history, and the
  // fee-transaction signer policy relies on C >= fixing-data-application-fee-validation (wallet authorization
  // without proof verification must be unreachable).
  test("the v4.1 cutover is never below any other threshold in any environment") {
    IO {
      val fields = ConfigSource.resources("application.conf").at("fields-added-ordinals").loadOrThrow[FieldsAddedOrdinals]
      AppEnvironment.values.toList.foldMap { environment =>
        val cutover = fields.tessellation41MigrationFor(environment)
        val above = fields.resolvedThresholdsFor(environment).filter { case (_, ordinal) => ordinal > cutover }
        expect(above.isEmpty, s"${environment.entryName}: thresholds above the cutover $cutover: $above")
      }
    }
  }

  pureTest("the shared ordinary-test fixture explicitly enables every current threshold") {
    val current = FieldsAddedOrdinalsFixtures.current
    val expected = Map(AppEnvironment.Dev -> SnapshotOrdinal.MinValue)
    val maps = current.productElementNames.zip(current.productIterator).filterNot(_._1 == "dustSweeps").toList
    expect.same(expectedThresholds.keySet, maps.map(_._1).toSet) &&
    expect(maps.forall(_._2 == expected)) &&
    expect(current.dustSweeps.isEmpty)
  }

  test("keeps every threshold gate disabled when an environment entry is absent") {
    val fieldsAddedOrdinals = disabledFieldsAddedOrdinals

    val absentEnvironmentThresholds = List(
      fieldsAddedOrdinals.tessellation3MigrationFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.tessellation301MigrationFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.checkSyncGlobalSnapshotFieldFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.metagraphSyncDataFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.updatedLastSyncGlobalOrderFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.updatedLastSyncGlobalFromPeersInConsensusFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.updatingCombineFunctionSpendActionsFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.fixingAllowSpendExpirationFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.fixingAllowSpendAndTokenLockValidationFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.fixingFeeTransactionBalanceOverflowFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.fixingDataApplicationFeeValidationFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.fixingAllowSpendDestinationCreditFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.preventingAllowSpendResurrectionFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.fixingGlobalAllowSpendExpirationFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.removingProcessedDelegatedStakeWithdrawalsFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.tessellation41MigrationFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.subTrieRootsFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.fixingDelegatedStakeDoubleWithdrawalFor(AppEnvironment.Mainnet)
    )

    IO {
      expect(absentEnvironmentThresholds.forall(_ === SnapshotOrdinal.MaxValue)) &&
      expect.same(SnapshotOrdinal.MaxValue, fieldsAddedOrdinals.feeTransactionSecurityFor(AppEnvironment.Mainnet)) &&
      expect.same(SnapshotOrdinal.MaxValue, fieldsAddedOrdinals.currencySnapshotProtocolV1For(AppEnvironment.Mainnet)) &&
      expect.same(SnapshotOrdinal.MaxValue, fieldsAddedOrdinals.fixingDelegatedStakeDoubleWithdrawalFor(AppEnvironment.Mainnet)) &&
      expect.same(SnapshotOrdinal.MaxValue, fieldsAddedOrdinals.fixingDataApplicationFeeValidationFor(AppEnvironment.Mainnet)) &&
      expect.same(SnapshotOrdinal.MaxValue, fieldsAddedOrdinals.fixingAllowSpendDestinationCreditFor(AppEnvironment.Mainnet)) &&
      expect(fieldsAddedOrdinals.dustSweepFor(AppEnvironment.Mainnet, SnapshotOrdinal.MinValue).isEmpty)
    }
  }

  test("resolves an exact-key dust sweep only for its configured environment and ordinal") {
    val activationOrdinal = SnapshotOrdinal.unsafeApply(1000L)
    val sweep = DustSweep(Balance.empty, None)
    val fieldsAddedOrdinals = disabledFieldsAddedOrdinals.copy(
      dustSweeps = Map(AppEnvironment.Dev -> SortedMap(activationOrdinal -> sweep))
    )

    IO {
      expect.same(Some(sweep), fieldsAddedOrdinals.dustSweepFor(AppEnvironment.Dev, activationOrdinal)) &&
      expect(fieldsAddedOrdinals.dustSweepFor(AppEnvironment.Dev, SnapshotOrdinal.unsafeApply(999L)).isEmpty) &&
      expect(fieldsAddedOrdinals.dustSweepFor(AppEnvironment.Mainnet, activationOrdinal).isEmpty)
    }
  }

}
