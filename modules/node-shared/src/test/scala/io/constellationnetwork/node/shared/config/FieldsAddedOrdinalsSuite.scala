package io.constellationnetwork.node.shared.config

import cats.effect.IO
import cats.syntax.eq._

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
    fixingAllowSpendAndTokenLockValidation = Map.empty,
    setSumFix = Map.empty
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
    "tessellation3Migration" -> allEnvironments(4409045L, 2497000L, 3330000L),
    "tessellation301Migration" -> allEnvironments(4915254L, 2500000L, 3584112L),
    "checkSyncGlobalSnapshotField" -> allEnvironments(4488000L, 2497000L, 3369135L),
    "metagraphSyncData" -> allEnvironments(4915254L, 2497000L, 3584112L),
    "updatedLastSyncGlobalOrder" -> allEnvironments(4915254L, 2691665L, 3648655L),
    "updatedLastSyncGlobalFromPeersInConsensus" -> allEnvironments(4915254L, 2694780L, 3669310L),
    "updatingCombineFunctionSpendActions" -> allEnvironments(4957662L, 2987405L, 3975600L),
    "fixingAllowSpendExpiration" -> allEnvironments(5033174L, 2987405L, 3975600L),
    "fixingAllowSpendAndTokenLockValidation" -> allEnvironments(5058096L, 9999999L, 5880000L),
    "setSumFix" -> allEnvironments(9999999L, 9999999L, 5880000L),
    "scFeeBalanceFromContext" -> allEnvironments(9999999L, 3101393L, 5880000L),
    "subTrieRoots" -> allEnvironments(9999999L, 9999999L, 5880000L),
    "delegatedRewardsFullCommittee" -> allEnvironments(9999999L, 9999999L, 5880000L),
    "feeTransactionSecurity" -> allEnvironments(9999999L, 9999999L, 5880000L),
    "fixingFeeTransactionBalanceOverflow" -> allEnvironments(6814499L, 3255000L, 5905000L),
    "currencySnapshotProtocolV1" -> Map(AppEnvironment.Dev -> SnapshotOrdinal.MinValue),
    "fixingDataApplicationFeeValidation" -> allEnvironments(6818000L, 9999999L, 9999999L),
    "fixingAllowSpendDestinationCredit" -> allEnvironments(6818000L, 9999999L, 9999999L),
    "preventingAllowSpendResurrection" -> allEnvironments(6828500L, 9999999L, 9999999L),
    "fixingGlobalAllowSpendExpiration" -> allEnvironments(6828500L, 9999999L, 9999999L)
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

  test("pins the separate historical hash, state-proof, staking boundaries and complete dust schedule") {
    IO {
      val source = ConfigSource.resources("application.conf")
      val fields = source.at("fields-added-ordinals").loadOrThrow[FieldsAddedOrdinals]
      expect.same(
        allEnvironments(2572384L, 1933590L, 1527434L),
        source.at("last-kryo-hash-ordinal").loadOrThrow[Map[AppEnvironment, SnapshotOrdinal]]
      ) &&
      expect.same(
        allEnvironments(5960000L, 3070000L, 5075000L),
        source.at("last-legacy-state-proof-ordinal").loadOrThrow[Map[AppEnvironment, SnapshotOrdinal]]
      ) &&
      expect.same(
        allEnvironments(5960000L, 3070000L, 5075000L),
        source.at("incremental-delegated-staking-starting-ordinal").loadOrThrow[Map[AppEnvironment, SnapshotOrdinal]]
      ) &&
      expect.same(
        Map(AppEnvironment.Testnet -> SortedMap(SnapshotOrdinal.unsafeApply(3154700L) -> DustSweep(Balance(100000L), None))),
        fields.dustSweeps
      )
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

  test("loads an explicit fee transaction security activation for every environment") {
    IO {
      ConfigSource.resources("application.conf").at("fields-added-ordinals").load[FieldsAddedOrdinals] match {
        case Left(failures) =>
          failure(failures.toList.mkString("\n"))
        case Right(fieldsAddedOrdinals) =>
          expect.same(
            Map(
              AppEnvironment.Mainnet -> SnapshotOrdinal.unsafeApply(9999999L),
              AppEnvironment.Testnet -> SnapshotOrdinal.unsafeApply(9999999L),
              AppEnvironment.Integrationnet -> SnapshotOrdinal.unsafeApply(5880000L),
              AppEnvironment.Dev -> SnapshotOrdinal.MinValue
            ),
            fieldsAddedOrdinals.feeTransactionSecurity
          )
      }
    }
  }

  test("aligns all IntegrationNet v4.1 activation gates") {
    IO {
      ConfigSource.resources("application.conf").at("fields-added-ordinals").load[FieldsAddedOrdinals] match {
        case Left(failures) =>
          failure(failures.toList.mkString("\n"))
        case Right(fieldsAddedOrdinals) =>
          val integrationnet = AppEnvironment.Integrationnet
          val activation = Some(SnapshotOrdinal.unsafeApply(5880000L))

          expect.same(
            Map(
              "fixing-allow-spend-and-token-lock-validation" ->
                fieldsAddedOrdinals.fixingAllowSpendAndTokenLockValidation.get(integrationnet),
              "set-sum-fix" -> fieldsAddedOrdinals.setSumFix.get(integrationnet),
              "sc-fee-balance-from-context" -> fieldsAddedOrdinals.scFeeBalanceFromContext.get(integrationnet),
              "sub-trie-roots" -> fieldsAddedOrdinals.subTrieRoots.get(integrationnet),
              "delegated-rewards-full-committee" -> fieldsAddedOrdinals.delegatedRewardsFullCommittee.get(integrationnet),
              "fee-transaction-security" -> fieldsAddedOrdinals.feeTransactionSecurity.get(integrationnet)
            ),
            Map(
              "fixing-allow-spend-and-token-lock-validation" -> activation,
              "set-sum-fix" -> activation,
              "sc-fee-balance-from-context" -> activation,
              "sub-trie-roots" -> activation,
              "delegated-rewards-full-committee" -> activation,
              "fee-transaction-security" -> activation
            )
          )
      }
    }
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
      fieldsAddedOrdinals.setSumFixFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.scFeeBalanceFromContextFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.subTrieRootsFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.delegatedRewardsFullCommitteeFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.feeTransactionSecurityFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.fixingFeeTransactionBalanceOverflowFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.currencySnapshotProtocolV1For(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.fixingDataApplicationFeeValidationFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.fixingAllowSpendDestinationCreditFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.preventingAllowSpendResurrectionFor(AppEnvironment.Mainnet),
      fieldsAddedOrdinals.fixingGlobalAllowSpendExpirationFor(AppEnvironment.Mainnet)
    )

    IO {
      expect(absentEnvironmentThresholds.forall(_ === SnapshotOrdinal.MaxValue)) &&
      expect.same(SnapshotOrdinal.MaxValue, fieldsAddedOrdinals.feeTransactionSecurityFor(AppEnvironment.Mainnet)) &&
      expect.same(SnapshotOrdinal.MaxValue, fieldsAddedOrdinals.currencySnapshotProtocolV1For(AppEnvironment.Mainnet)) &&
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

  test("Currency snapshot protocol v1 is enabled for dev and fails closed for every public environment") {
    IO {
      ConfigSource.resources("application.conf").at("fields-added-ordinals").load[FieldsAddedOrdinals] match {
        case Left(failures) =>
          failure(failures.toList.mkString("\n"))
        case Right(fieldsAddedOrdinals) =>
          expect.same(SnapshotOrdinal.MinValue, fieldsAddedOrdinals.currencySnapshotProtocolV1For(AppEnvironment.Dev)) &&
          expect.same(SnapshotOrdinal.MaxValue, fieldsAddedOrdinals.currencySnapshotProtocolV1For(AppEnvironment.Integrationnet)) &&
          expect.same(SnapshotOrdinal.MaxValue, fieldsAddedOrdinals.currencySnapshotProtocolV1For(AppEnvironment.Testnet)) &&
          expect.same(SnapshotOrdinal.MaxValue, fieldsAddedOrdinals.currencySnapshotProtocolV1For(AppEnvironment.Mainnet))
      }
    }
  }

  test("the protocol-v1 gate preserves the latest develop positional constructor") {
    val ordinals = Map.empty[AppEnvironment, SnapshotOrdinal]
    val legacyShape = FieldsAddedOrdinals(
      ordinals,
      ordinals,
      ordinals,
      ordinals,
      ordinals,
      ordinals,
      ordinals,
      ordinals,
      ordinals,
      ordinals,
      ordinals,
      ordinals,
      ordinals,
      ordinals,
      ordinals,
      Map.empty[AppEnvironment, SortedMap[SnapshotOrdinal, DustSweep]]
    )

    IO(expect.same(Map.empty, legacyShape.currencySnapshotProtocolV1))
  }
}
