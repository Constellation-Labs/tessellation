package io.constellationnetwork.node.shared.config

import scala.concurrent.duration._

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig, SharedConfigReader}
import io.constellationnetwork.node.shared.ext.pureconfig._
import io.constellationnetwork.schema.SnapshotOrdinal

import com.typesafe.config.ConfigValueFactory
import eu.timepit.refined.auto._
import eu.timepit.refined.pureconfig._
import pureconfig.ConfigSource
import pureconfig.generic.auto._
import pureconfig.module.enumeratum._
import weaver.SimpleIOSuite

object ConsensusOrdinalConfigSuite extends SimpleIOSuite {
  // SharedConfigReader also requires this layer-specific setting; both L0 configs supply it.
  private val source = ConfigSource
    .string("state-after-joining = WaitingForDownload")
    .withFallback(ConfigSource.resources("application.conf"))
  private lazy val packaged = source.loadOrThrow[SharedConfigReader]
  private lazy val raw = source.config().fold(errors => throw new IllegalStateException(errors.toString), identity)
  private val base = ConsensusConfig(10.seconds, 10.seconds, 100L, 10.seconds, EventCutterConfig(1024, 1024))

  private def hash(config: SharedConfigReader, environment: AppEnvironment) =
    base.withSharedConfig(config, environment).deterministicConfigHash

  private def replace(path: String, value: AnyRef): SharedConfigReader =
    ConfigSource.fromConfig(raw.withValue(path, ConfigValueFactory.fromAnyRef(value))).loadOrThrow[SharedConfigReader]

  // Derive the inventory from the case class, not the hash implementation, so an omitted new
  // activation input fails this suite. Dust schedules are tested separately below.
  private val thresholdPaths = FieldsAddedOrdinalsFixtures.current.productElementNames
    .filterNot(_ == "dustSweeps")
    .map {
      case "tessellation3Migration"   => "fields-added-ordinals.tessellation-3-migration"
      case "tessellation301Migration" => "fields-added-ordinals.tessellation-301-migration"
      case "tessellation41Migration"  => "fields-added-ordinals.tessellation-41-migration"
      case name                       => "fields-added-ordinals." + name.replaceAll("([A-Z])", "-$1").toLowerCase
    }
    .toList :+ "last-kryo-hash-ordinal"

  AppEnvironment.values.foreach { environment =>
    thresholdPaths.foreach { path =>
      pureTest(s"$path: a different ${environment.entryName} activation changes the consensus hash") {
        val changed = replace(s"$path.${environment.entryName}", Long.box(42L))
        expect(hash(packaged, environment) != hash(changed, environment))
      }
    }
  }

  thresholdPaths.foreach { path =>
    pureTest(s"$path: removing an explicit Mainnet activation changes the consensus hash") {
      val incomplete = ConfigSource.fromConfig(raw.withoutPath(s"$path.mainnet")).loadOrThrow[SharedConfigReader]
      expect(hash(packaged, AppEnvironment.Mainnet) != hash(incomplete, AppEnvironment.Mainnet))
    }
  }

  pureTest("only the running environment's activation values enter its consensus hash") {
    val changed = replace("fields-added-ordinals.fixing-fee-transaction-balance-overflow.mainnet", Long.box(42L))
    expect.same(hash(packaged, AppEnvironment.Dev), hash(changed, AppEnvironment.Dev)) &&
    expect.same(hash(packaged, AppEnvironment.Testnet), hash(changed, AppEnvironment.Testnet))
  }

  pureTest("absent first-active thresholds hash identically to explicit disabled thresholds") {
    val path = "fields-added-ordinals.tessellation-41-migration.mainnet"
    val absent = ConfigSource.fromConfig(raw.withoutPath(path)).loadOrThrow[SharedConfigReader]
    val explicit = replace(path, Long.box(Long.MaxValue))
    expect.same(hash(absent, AppEnvironment.Mainnet), hash(explicit, AppEnvironment.Mainnet))
  }

  pureTest("last-legacy defaults retain their existing distinct meanings") {
    val missing = packaged.copy(
      lastKryoHashOrdinal = Map.empty,
      fieldsAddedOrdinals = packaged.fieldsAddedOrdinals.copy(tessellation41Migration = Map.empty)
    )
    val explicit = missing.copy(lastKryoHashOrdinal = Map(AppEnvironment.Mainnet -> SnapshotOrdinal.MinValue))
    expect.same(SnapshotOrdinal.MinValue, missing.lastKryoHashOrdinalFor(AppEnvironment.Mainnet)) &&
    expect.same(SnapshotOrdinal.MaxValue, missing.lastLegacyStateProofOrdinalFor(AppEnvironment.Mainnet)) &&
    expect.same(SnapshotOrdinal.MaxValue, missing.incrementalDelegatedStakingStartingOrdinalFor(AppEnvironment.Mainnet)) &&
    expect.same(hash(missing, AppEnvironment.Mainnet), hash(explicit, AppEnvironment.Mainnet))
  }

  pureTest("the legacy state-proof and incremental staking boundaries move with the v4.1 cutover") {
    val moved = replace("fields-added-ordinals.tessellation-41-migration.mainnet", Long.box(7100001L))
    expect.same(SnapshotOrdinal.unsafeApply(7100000L), moved.lastLegacyStateProofOrdinalFor(AppEnvironment.Mainnet)) &&
    expect.same(SnapshotOrdinal.unsafeApply(7100000L), moved.incrementalDelegatedStakingStartingOrdinalFor(AppEnvironment.Mainnet)) &&
    expect(hash(packaged, AppEnvironment.Mainnet) != hash(moved, AppEnvironment.Mainnet))
  }

  // No network ships a dust sweep, so the schedule tests install one.
  private val sweepPath = "fields-added-ordinals.dust-sweeps.testnet.3154700"
  private val withSweep = raw.withValue(s"$sweepPath.threshold", ConfigValueFactory.fromAnyRef(Long.box(100000L)))
  private lazy val packagedWithSweep = ConfigSource.fromConfig(withSweep).loadOrThrow[SharedConfigReader]

  pureTest("changing a dust sweep's ordinal, threshold, or burn/credit destination changes the hash") {
    def load(config: com.typesafe.config.Config) = ConfigSource.fromConfig(config).loadOrThrow[SharedConfigReader]
    val threshold = load(withSweep.withValue(s"$sweepPath.threshold", ConfigValueFactory.fromAnyRef(Long.box(100001L))))
    val destination = load(
      withSweep.withValue(s"$sweepPath.collection-address", ConfigValueFactory.fromAnyRef("DAG0CyySf35ftDQDQBnd1bdQ9aPyUdacMghpnCuM"))
    )
    val ordinal = load(
      withSweep
        .withoutPath(sweepPath)
        .withValue("fields-added-ordinals.dust-sweeps.testnet.3154701", withSweep.getValue(sweepPath))
    )
    val original = hash(packagedWithSweep, AppEnvironment.Testnet)
    expect(hash(packaged, AppEnvironment.Testnet) != original, "installing a sweep changes the hash")
      .and(expect(List(threshold, destination, ordinal).forall(config => hash(config, AppEnvironment.Testnet) != original)))
  }

  pureTest("the complete dust schedule is hashed independently of configuration key order") {
    val first = "fields-added-ordinals.dust-sweeps.testnet.3154701.threshold"
    val second = "fields-added-ordinals.dust-sweeps.testnet.3154702.threshold"
    val one = ConfigValueFactory.fromAnyRef(Long.box(1L))
    val two = ConfigValueFactory.fromAnyRef(Long.box(2L))
    val forward = ConfigSource.fromConfig(raw.withValue(first, one).withValue(second, two)).loadOrThrow[SharedConfigReader]
    val reverse = ConfigSource.fromConfig(raw.withValue(second, two).withValue(first, one)).loadOrThrow[SharedConfigReader]
    expect.same(hash(forward, AppEnvironment.Testnet), hash(reverse, AppEnvironment.Testnet)) &&
    expect(hash(packaged, AppEnvironment.Testnet) != hash(forward, AppEnvironment.Testnet)) &&
    expect.same(hash(packaged, AppEnvironment.Mainnet), hash(forward, AppEnvironment.Mainnet))
  }

  pureTest("the effective L0 config includes shared activation values before it is used for joining or consensus") {
    val effective = base.withSharedConfig(packaged, AppEnvironment.Dev)
    expect.same(Some(packaged.ordinalConfigHashFor(AppEnvironment.Dev)), effective.ordinalConfigHash) &&
    expect.same(0L, effective.currencySnapshotProtocolV1ActivationOrdinal) &&
    expect.same(packaged.lastGlobalSnapshotsSync.syncOffset.value, effective.lastGlobalSnapshotSyncOffset) &&
    expect.same(packaged.lastGlobalSnapshotsSync.maxLastGlobalSnapshotsInMemory.value, effective.lastGlobalSnapshotsInMemory) &&
    expect(effective.deterministicConfigHash != base.deterministicConfigHash)
  }

  pureTest("removing the required activation block fails config loading rather than silently disabling every gate") {
    expect(source.load[SharedConfigReader].isRight) &&
    expect(ConfigSource.fromConfig(raw.withoutPath("fields-added-ordinals")).load[SharedConfigReader].isLeft) &&
    expect(ConfigSource.fromConfig(raw.withoutPath("fields-added-ordinals.tessellation-3-migration")).load[SharedConfigReader].isLeft)
  }
}
