package io.constellationnetwork.node.shared.config

import cats.effect.IO

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types.FieldsAddedOrdinals
import io.constellationnetwork.node.shared.ext.pureconfig._
import io.constellationnetwork.schema.SnapshotOrdinal

import pureconfig.ConfigSource
import pureconfig.generic.auto._
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
              AppEnvironment.Integrationnet -> SnapshotOrdinal.unsafeApply(9999999L),
              AppEnvironment.Dev -> SnapshotOrdinal.MinValue
            ),
            fieldsAddedOrdinals.feeTransactionSecurity
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
}
