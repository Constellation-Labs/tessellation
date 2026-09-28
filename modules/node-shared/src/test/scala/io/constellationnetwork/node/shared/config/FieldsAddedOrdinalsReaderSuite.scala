package io.constellationnetwork.node.shared.config

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types.FieldsAddedOrdinals
import io.constellationnetwork.node.shared.ext.pureconfig._
import io.constellationnetwork.schema.SnapshotOrdinal

import com.typesafe.config.{Config, ConfigValueFactory}
import pureconfig.ConfigSource
import weaver.SimpleIOSuite

object FieldsAddedOrdinalsReaderSuite extends SimpleIOSuite {
  private val root = "fields-added-ordinals"

  private val raw: Config = ConfigSource
    .resources("application.conf")
    .config()
    .fold(errors => throw new IllegalStateException(errors.toString), identity)

  private def load(config: Config) = ConfigSource.fromConfig(config).at(root).load[FieldsAddedOrdinals]

  private def keyOf(field: String): String = field match {
    case "tessellation3Migration"   => "tessellation-3-migration"
    case "tessellation301Migration" => "tessellation-301-migration"
    case name                       => name.replaceAll("([A-Z])", "-$1").toLowerCase
  }

  // Derived from the case class so a newly appended gate is covered without editing this suite.
  private val ordinalGateFields: List[(String, Int)] =
    FieldsAddedOrdinalsFixtures.current.productElementNames.zipWithIndex.filterNot { case (name, _) => name == "dustSweeps" }.toList

  private def distinctOrdinal(fieldIndex: Int, environment: AppEnvironment): SnapshotOrdinal =
    SnapshotOrdinal.unsafeApply(1000L * (fieldIndex + 1) + AppEnvironment.values.indexOf(environment).toLong)

  private val distinctConfig: Config =
    ordinalGateFields.foldLeft(raw) {
      case (config, (field, index)) =>
        AppEnvironment.values.foldLeft(config.withoutPath(s"$root.${keyOf(field)}")) { (acc, environment) =>
          acc.withValue(
            s"$root.${keyOf(field)}.${environment.entryName}",
            ConfigValueFactory.fromAnyRef(Long.box(distinctOrdinal(index, environment).value.value))
          )
        }
    }

  pureTest("the packaged configuration loads") {
    val result = load(raw)
    expect(result.isRight, s"packaged fields-added-ordinals failed to load: $result")
  }

  ordinalGateFields.foreach {
    case (field, index) =>
      pureTest(s"$field is read from its own key ${keyOf(field)}") {
        val expected = AppEnvironment.values.map(environment => environment -> distinctOrdinal(index, environment)).toMap

        load(distinctConfig).fold(
          errors => failure(s"configuration failed to load: $errors"),
          loaded => expect.same(expected, loaded.productElement(index))
        )
      }
  }

  pureTest("dust sweeps are read from their own key") {
    val packagedSweeps = raw.getConfig(s"$root.dust-sweeps").root().keySet()

    load(raw).fold(
      errors => failure(s"configuration failed to load: $errors"),
      loaded =>
        expect(
          loaded.dustSweeps.keySet.map(_.entryName) == Set.from(packagedSweeps.toArray.map(_.toString)),
          s"dust sweep environments: ${loaded.dustSweeps.keySet}"
        )
    )
  }

  ordinalGateFields.foreach {
    case (field, _) =>
      pureTest(s"a missing ${keyOf(field)} key fails loading and is named in the error") {
        load(raw.withoutPath(s"$root.${keyOf(field)}")).fold(
          errors => expect(errors.prettyPrint().contains(keyOf(field)), s"error does not name the key: ${errors.prettyPrint()}"),
          _ => failure(s"loading succeeded without ${keyOf(field)}")
        )
      }
  }

  pureTest("unknown keys are ignored") {
    val withUnknown = raw.withValue(s"$root.not-a-gate.mainnet", ConfigValueFactory.fromAnyRef(Long.box(1L)))
    expect.same(load(raw), load(withUnknown))
  }
}
