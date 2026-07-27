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

  test("activates fee transaction security immediately when an environment entry is absent") {
    val fieldsAddedOrdinals = FieldsAddedOrdinals(
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty
    )

    IO(
      expect.same(
        SnapshotOrdinal.MinValue,
        fieldsAddedOrdinals.feeTransactionSecurityFor(AppEnvironment.Mainnet)
      )
    )
  }
}
