package io.constellationnetwork.currency.l0.snapshot

import cats.data.NonEmptyList
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration._

import io.constellationnetwork.currency.dataApplication.storage.{
  GlobalSnapshotsWithStateDeltasLocalFileSystemStorage,
  GlobalSnapshotsWithStateLocalFileSystemStorage
}
import io.constellationnetwork.currency.l0.StateChannel
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSyncView
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.LastGlobalSnapshotsSyncConfig
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.LastSyncGlobalSnapshotStorage
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops._
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.types.numeric.{NonNegLong, PosInt, PosLong}
import fs2.io.file.{Files, Path}
import weaver.MutableIOSuite

object RecoveryGlobalAnchorSelectionSuite extends MutableIOSuite {

  final case class TestContext(serializer: JsonSerializer[IO], hasher: Hasher[IO], securityProvider: SecurityProvider[IO])

  override type Res = TestContext

  override def sharedResource: Resource[IO, Res] =
    for {
      serializer <- Resource.eval(JsonSerializer.forAsync[IO])
      securityProvider <- SecurityProvider.forAsync[IO]
      hasher = {
        implicit val jsonSerializer: JsonSerializer[IO] = serializer
        Hasher.forJson[IO]
      }
    } yield TestContext(serializer, hasher, securityProvider)

  private implicit val stateProofSelector: GlobalStateProofSelector =
    GlobalStateProofSelector(SnapshotOrdinal(NonNegLong(Long.MaxValue)))

  private val syncOffset = NonNegLong(2L)
  private val initialPull = SnapshotOrdinal.unsafeApply(70L)

  private val info =
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
      Some(SortedMap.empty),
      Some(SortedMap.empty)
    )

  private def snapshot(
    ordinal: SnapshotOrdinal,
    parentHash: Hash,
    keyPair: java.security.KeyPair
  )(
    implicit hasher: Hasher[IO],
    serializer: JsonSerializer[IO],
    securityProvider: SecurityProvider[IO]
  ): IO[Hashed[GlobalIncrementalSnapshot]] =
    info.stateProof[IO](ordinal).flatMap { stateProof =>
      Signed
        .forAsyncHasher[IO, GlobalIncrementalSnapshot](
          GlobalIncrementalSnapshot(
            ordinal,
            Height(ordinal.value),
            SubHeight.MinValue,
            parentHash,
            SortedSet.empty,
            SortedMap.empty,
            SortedSet.empty,
            None,
            EpochProgress.MinValue,
            NonEmptyList.one(PeerId.fromId(keyPair.getPublic.toId)),
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
          keyPair
        )
        .flatMap(_.toHashed[IO])
    }

  private def chain(
    first: Long,
    last: Long,
    keyPair: java.security.KeyPair
  )(
    implicit hasher: Hasher[IO],
    serializer: JsonSerializer[IO],
    securityProvider: SecurityProvider[IO]
  ): IO[Map[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]]] =
    (first to last).toList
      .foldLeftM((Hash.empty, Map.empty[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]])) {
        case ((parentHash, acc), value) =>
          val ordinal = SnapshotOrdinal.unsafeApply(value)
          snapshot(ordinal, parentHash, keyPair).map(hashed => (hashed.hash, acc.updated(ordinal, hashed)))
      }
      .map(_._2)

  private val unusedCurrencySnapshots: SnapshotStorage[IO, CurrencyIncrementalSnapshot, CurrencySnapshotInfo] =
    new SnapshotStorage[IO, CurrencyIncrementalSnapshot, CurrencySnapshotInfo] {
      private def unused[A]: IO[A] = IO.raiseError(new IllegalStateException("unexpected currency snapshot storage operation"))

      def prepend(snapshot: Signed[CurrencyIncrementalSnapshot], state: CurrencySnapshotInfo)(implicit hasher: Hasher[IO]): IO[Boolean] =
        unused
      def head: IO[Option[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]] = unused
      def headSnapshot: IO[Option[Signed[CurrencyIncrementalSnapshot]]] = unused
      def get(ordinal: SnapshotOrdinal): IO[Option[Signed[CurrencyIncrementalSnapshot]]] = unused
      def getHashed(ordinal: SnapshotOrdinal)(implicit hasher: Hasher[IO]): IO[Option[Hashed[CurrencyIncrementalSnapshot]]] = unused
      def get(hash: Hash): IO[Option[Signed[CurrencyIncrementalSnapshot]]] = unused
      def getHash(ordinal: SnapshotOrdinal)(implicit hasher: Hasher[IO]): IO[Option[Hash]] = unused
      def setHeadForRecovery(snapshot: Signed[CurrencyIncrementalSnapshot], state: CurrencySnapshotInfo)(
        implicit hasher: Hasher[IO]
      ): IO[Unit] = unused
    }

  private final case class ColdStartedLead(
    lastSyncGlobalSnapshot: io.constellationnetwork.node.shared.domain.snapshot.storage.LastSyncGlobalSnapshotStorage[IO],
    globalSnapshotsWithState: GlobalSnapshotsWithStateLocalFileSystemStorage[IO],
    refresh: IO[Unit],
    recentGlobalSnapshots: IO[List[Hashed[GlobalIncrementalSnapshot]]],
    refreshes: Ref[IO, Int]
  )

  /** A rollback lead whose first Global L0 pull is the snapshot it will anchor at. It holds full Global state only from that pull,
    * while its retained hash window reaches further back, as `setInitialFetchingGL0` fills it from Global L0. Each refresh processes,
    * in order, the snapshots Global L0 has produced since the previous refresh: `producedPerRefresh` after the first refresh.
    */
  private def coldStartedLead(
    directory: Path,
    canonical: Map[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]],
    producedPerRefresh: Long
  ): IO[ColdStartedLead] =
    for {
      globalSnapshotsWithState <- GlobalSnapshotsWithStateLocalFileSystemStorage
        .make[IO](directory / "global-snapshots-with-state", PosLong(1000L))
      globalSnapshotsWithStateDeltas <- GlobalSnapshotsWithStateDeltasLocalFileSystemStorage
        .make[IO](directory / "global-snapshots-with-state-deltas", PosLong(1000L))
      lastSyncGlobalSnapshot <- LastSyncGlobalSnapshotStorage.make[IO](
        LastGlobalSnapshotsSyncConfig(syncOffset, PosInt(50)),
        unusedCurrencySnapshots,
        globalSnapshotsWithState,
        globalSnapshotsWithStateDeltas
      )
      _ <- lastSyncGlobalSnapshot.setInitial(canonical(initialPull), info)
      refreshes <- Ref.of[IO, Int](0)
      lastProduced = canonical.keys.max.value.value
      refresh = refreshes.updateAndGet(_ + 1).flatMap { count =>
        val globalL0Tip = Math.min(initialPull.value.value + (count - 1) * producedPerRefresh, lastProduced)
        lastSyncGlobalSnapshot.getOrdinal.flatMap(_.traverse_ { held =>
          (held.value.value + 1 to globalL0Tip).toList.traverse_ { value =>
            lastSyncGlobalSnapshot.set(canonical(SnapshotOrdinal.unsafeApply(value)), info)
          }
        })
      }
      recentGlobalSnapshots = lastSyncGlobalSnapshot.getOrdinal.map(_.toList.flatMap { tip =>
        canonical.values.toList
          .filter(value => value.ordinal <= tip && value.ordinal.value.value > tip.value.value - 50L)
          .sortBy(_.ordinal)
      })
    } yield ColdStartedLead(lastSyncGlobalSnapshot, globalSnapshotsWithState, refresh, recentGlobalSnapshots, refreshes)

  /** The first successor's fee capture, resolving Global state exactly as Currency L0 consensus does. */
  private def captureFirstSuccessorFeeContext(
    lead: ColdStartedLead,
    view: GlobalSyncView
  )(implicit hasher: Hasher[IO]): IO[Unit] =
    CurrencySnapshotConsensusFunctions.captureFeeContextReceipt[IO](
      SnapshotOrdinal.unsafeApply(10L),
      Hash("first-successor"),
      view.some,
      none,
      ordinal =>
        lead.lastSyncGlobalSnapshot
          .getCombined(ordinal)
          .flatMap(_.traverse { case (snapshot, _) => snapshot.hash.map(_ -> Balance.empty) }),
      _ => IO.unit
    )

  private def canonicalChain(
    implicit hasher: Hasher[IO],
    serializer: JsonSerializer[IO],
    securityProvider: SecurityProvider[IO]
  ): IO[Map[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]]] =
    KeyPairGenerator.makeKeyPair[IO].flatMap(chain(20L, 80L, _))

  test("a lead whose first Global pull is the anchor arms only once the first successor's fee context is held") { implicit res =>
    implicit val serializer: JsonSerializer[IO] = res.serializer
    implicit val hasher: Hasher[IO] = res.hasher
    implicit val securityProvider: SecurityProvider[IO] = res.securityProvider
    Files[IO].tempDirectory.use { directory =>
      for {
        canonical <- canonicalChain
        lead <- coldStartedLead(directory, canonical, producedPerRefresh = 1L)
        (anchor, selectedTarget) <- StateChannel.selectRecoveryGlobalAnchor[IO](
          lead.refresh,
          lead.lastSyncGlobalSnapshot,
          lead.recentGlobalSnapshots,
          syncOffset,
          maxRefreshes = 5,
          retryDelay = Duration.Zero
        )
        capture <- captureFirstSuccessorFeeContext(
          lead,
          GlobalSyncView(selectedTarget, canonical(selectedTarget).hash, EpochProgress.MinValue)
        ).attempt
      } yield
        expect.same(Right(()), capture) &&
          expect.same(SnapshotOrdinal.unsafeApply(72L), anchor.ordinal) &&
          expect.same(initialPull, selectedTarget)
    }
  }

  test("recovery arming fails closed when the selected target's Global state never becomes held") { implicit res =>
    implicit val serializer: JsonSerializer[IO] = res.serializer
    implicit val hasher: Hasher[IO] = res.hasher
    implicit val securityProvider: SecurityProvider[IO] = res.securityProvider
    Files[IO].tempDirectory.use { directory =>
      for {
        canonical <- canonicalChain
        lead <- coldStartedLead(directory, canonical, producedPerRefresh = 0L)
        result <- StateChannel
          .selectRecoveryGlobalAnchor[IO](
            lead.refresh,
            lead.lastSyncGlobalSnapshot,
            lead.recentGlobalSnapshots,
            syncOffset,
            maxRefreshes = 3,
            retryDelay = Duration.Zero
          )
          .attempt
        refreshes <- lead.refreshes.get
      } yield
        expect(
          result.left.exists(error =>
            error.isInstanceOf[IllegalStateException] && error.getMessage.contains("state is not held after 3 refreshes")
          )
        ) &&
          expect.same(3, refreshes)
    }
  }

  test("recovery arming fails closed without waiting when the held target state conflicts with the retained window") { implicit res =>
    implicit val serializer: JsonSerializer[IO] = res.serializer
    implicit val hasher: Hasher[IO] = res.hasher
    implicit val securityProvider: SecurityProvider[IO] = res.securityProvider
    Files[IO].tempDirectory.use { directory =>
      for {
        canonical <- canonicalChain
        lead <- coldStartedLead(directory, canonical, producedPerRefresh = 1L)
        otherKeyPair <- KeyPairGenerator.makeKeyPair[IO]
        conflicting <- snapshot(SnapshotOrdinal.unsafeApply(68L), Hash.empty, otherKeyPair)
        _ <- lead.globalSnapshotsWithState.write(conflicting.ordinal, GlobalSnapshotWithState(conflicting.signed, info))
        result <- StateChannel
          .selectRecoveryGlobalAnchor[IO](
            lead.refresh,
            lead.lastSyncGlobalSnapshot,
            lead.recentGlobalSnapshots,
            syncOffset,
            maxRefreshes = 5,
            retryDelay = Duration.Zero
          )
          .attempt
        refreshes <- lead.refreshes.get
      } yield
        expect(
          result.left.exists(error =>
            error.isInstanceOf[IllegalStateException] && error.getMessage.contains("conflicts with the canonical recent window")
          )
        ) &&
          expect.same(1, refreshes)
    }
  }
}
