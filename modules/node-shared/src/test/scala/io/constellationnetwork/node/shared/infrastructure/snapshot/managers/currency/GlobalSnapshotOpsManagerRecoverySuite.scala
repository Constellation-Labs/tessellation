package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.{IO, Ref}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSyncView
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.LastGlobalSnapshotsSyncConfig
import io.constellationnetwork.node.shared.infrastructure.metrics.{Metrics, NoOpMetrics}
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency.GlobalSnapshotOpsManager.{
  HistoricalReplay,
  LiveBounded,
  RecoveryEpoch
}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.{Hashed, Hasher}

import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import fs2.concurrent.SignallingRef
import weaver.SimpleIOSuite

object GlobalSnapshotOpsManagerRecoverySuite extends SimpleIOSuite {

  private implicit val metrics: Metrics[IO] = NoOpMetrics.make
  private implicit val stateProofSelector: GlobalStateProofSelector =
    GlobalStateProofSelector(SnapshotOrdinal(NonNegLong(Long.MaxValue)))

  private def info =
    GlobalSnapshotInfo(
      SortedMap.empty,
      SortedMap.empty,
      SortedMap.empty,
      SortedMap.empty,
      SortedMap.empty,
      None,
      None,
      None,
      None,
      None,
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty)
    )

  private def snapshot(ordinal: Long)(implicit hasher: Hasher[IO], serializer: JsonSerializer[IO]): IO[Hashed[GlobalIncrementalSnapshot]] = {
    val value = SnapshotOrdinal.unsafeApply(ordinal)

    info.stateProof[IO](value).flatMap { stateProof =>
      Signed(
        GlobalIncrementalSnapshot(
          value,
          Height.MinValue,
          SubHeight.MinValue,
          Hash.empty,
          SortedSet.empty,
          SortedMap.empty,
          SortedSet.empty,
          None,
          EpochProgress.MinValue,
          NonEmptyList.one(PeerId(Hex("peer"))),
          SnapshotTips(SortedSet.empty, SortedSet.empty),
          stateProof,
          Some(SortedSet.empty),
          Some(SortedSet.empty),
          Some(SortedMap.empty),
          Some(SortedMap.empty),
          Some(SortedSet.empty),
          Some(SortedMap.empty),
          Some(SortedMap.empty),
          Some(SortedMap.empty),
          Some(SortedMap.empty)
        ),
        NonEmptySet.one(SignatureProof(ID.Id(Hex("peer")), Signature(Hex("signature"))))
      ).toHashed[IO]
    }
  }

  private def manager: IO[GlobalSnapshotOpsManager[IO]] =
    SignallingRef
      .of[IO, Map[Address, Map[SnapshotOrdinal, List[SnapshotOrdinal]]]](Map.empty)
      .map(new GlobalSnapshotOpsManager[IO](LastGlobalSnapshotsSyncConfig(NonNegLong(2L), PosInt(50)), _))

  test("historical replay preserves the rc.12 callback while live processing never consults it") {
    for {
      implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
      ops <- manager
      old <- snapshot(10L)
      parent <- snapshot(100L)
      calls <- Ref.of[IO, Int](0)
      callback = (ordinal: SnapshotOrdinal) => calls.update(_ + 1).as(Option.when(ordinal === old.ordinal)(old))
      historical <- ops.resolveGlobalSnapshot(
        HistoricalGlobalSnapshotResolver.SyncTarget,
        old.ordinal,
        parent.ordinal,
        List(parent),
        callback,
        HistoricalReplay
      )
      afterHistorical <- calls.get
      live <- ops
        .resolveGlobalSnapshot(
          HistoricalGlobalSnapshotResolver.SyncTarget,
          old.ordinal,
          parent.ordinal,
          List(parent),
          callback,
          LiveBounded
        )
        .attempt
      afterLive <- calls.get
    } yield
      expect.same(old.hash, historical.hash) &&
        expect.same(1, afterHistorical) &&
        expect(live.swap.exists(_.isInstanceOf[HistoricalGlobalSnapshotResolver.OutsideRetainedWindow])) &&
        expect.same(afterHistorical, afterLive)
  }

  pureTest("the signed recovery epoch remains bounded during historical recreation") {
    expect.same(RecoveryEpoch, GlobalSnapshotOpsManager.selectDependencyMode(historicalReplay = true, recoveryEpochActive = true)) &&
    expect.same(HistoricalReplay, GlobalSnapshotOpsManager.selectDependencyMode(historicalReplay = true, recoveryEpochActive = false)) &&
    expect.same(LiveBounded, GlobalSnapshotOpsManager.selectDependencyMode(historicalReplay = false, recoveryEpochActive = false))
  }

  pureTest("a validated reset replaces a numerically newer orphaned parent sync view") {
    val orphaned = GlobalSyncView(SnapshotOrdinal.unsafeApply(500L), Hash("orphaned"), EpochProgress.MinValue)
    val canonical = GlobalSyncView(SnapshotOrdinal.unsafeApply(98L), Hash("canonical"), EpochProgress.MinValue)

    expect.same(canonical, CurrencySnapshotAcceptanceManager.selectGlobalSyncView(orphaned.some, canonical, isRecoveryReset = true)) &&
    expect.same(orphaned, CurrencySnapshotAcceptanceManager.selectGlobalSyncView(orphaned.some, canonical, isRecoveryReset = false))
  }

  test("an in-range gap is missing_recent and does not fall back to local archive or network") {
    for {
      implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
      ops <- manager
      parent <- snapshot(100L)
      calls <- Ref.of[IO, Int](0)
      result <- ops
        .resolveGlobalSnapshot(
          HistoricalGlobalSnapshotResolver.UnappliedSpendAction,
          SnapshotOrdinal.unsafeApply(75L),
          parent.ordinal,
          List(parent),
          _ => calls.update(_ + 1).as(none),
          LiveBounded
        )
        .attempt
      count <- calls.get
    } yield
      expect(result.swap.exists(_.isInstanceOf[HistoricalGlobalSnapshotResolver.MissingInsideRetainedWindow])) &&
        expect.same(0, count)
  }
}
