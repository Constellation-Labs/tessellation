package io.constellationnetwork.node.shared.config

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types.{DustSweep, FieldsAddedOrdinals}
import io.constellationnetwork.node.shared.ext.pureconfig._
import io.constellationnetwork.schema.SnapshotOrdinal

import pureconfig.ConfigSource
import weaver.SimpleIOSuite

object FieldsAddedOrdinalsSuite extends SimpleIOSuite {

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

  test("keeps fee transaction security disabled when an environment entry is absent") {
    val fieldsAddedOrdinals = FieldsAddedOrdinals(
      tessellation3Migration = Map.empty,
      tessellation301Migration = Map.empty,
      checkSyncGlobalSnapshotField = Map.empty,
      metagraphSyncData = Map.empty,
      updatedLastSyncGlobalOrder = Map.empty,
      updatedLastSyncGlobalFromPeersInConsensus = Map.empty,
      updatingCombineFunctionSpendActions = Map.empty,
      fixingAllowSpendExpiration = Map.empty,
      fixingAllowSpendAndTokenLockValidation = Map.empty,
      setSumFix = Map.empty,
      feeTransactionSecurity = Map.empty
    )

    IO(
      expect.same(
        SnapshotOrdinal.MaxValue,
        fieldsAddedOrdinals.feeTransactionSecurityFor(AppEnvironment.Mainnet)
      )
    )
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

  test("delegated stake double-withdrawal hardening is enabled for dev and fails closed for public environments") {
    IO {
      ConfigSource.resources("application.conf").at("fields-added-ordinals").load[FieldsAddedOrdinals] match {
        case Left(failures) =>
          failure(failures.toList.mkString("\n"))
        case Right(fieldsAddedOrdinals) =>
          expect.same(
            SnapshotOrdinal.MinValue,
            fieldsAddedOrdinals.fixingDelegatedStakeDoubleWithdrawalFor(AppEnvironment.Dev)
          ) &&
          expect.same(
            SnapshotOrdinal.MaxValue,
            fieldsAddedOrdinals.fixingDelegatedStakeDoubleWithdrawalFor(AppEnvironment.Integrationnet)
          ) &&
          expect.same(
            SnapshotOrdinal.MaxValue,
            fieldsAddedOrdinals.fixingDelegatedStakeDoubleWithdrawalFor(AppEnvironment.Testnet)
          ) &&
          expect.same(
            SnapshotOrdinal.MaxValue,
            fieldsAddedOrdinals.fixingDelegatedStakeDoubleWithdrawalFor(AppEnvironment.Mainnet)
          )
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
