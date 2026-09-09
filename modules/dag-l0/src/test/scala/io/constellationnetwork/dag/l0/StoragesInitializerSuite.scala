package io.constellationnetwork.dag.l0

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.LastGlobalSnapshotsSyncConfig
import io.constellationnetwork.node.shared.domain.snapshot.programs.Download
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.LastNGlobalSnapshotStorage
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops._
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import fs2.Stream
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import weaver.SimpleIOSuite

object StoragesInitializerSuite extends SimpleIOSuite {

  private implicit val stateProofSelector: GlobalStateProofSelector =
    GlobalStateProofSelector(SnapshotOrdinal(NonNegLong(Long.MaxValue)))

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

  private def chain(first: Long, last: Long, keyPair: java.security.KeyPair)(
    implicit hasher: Hasher[IO],
    serializer: JsonSerializer[IO],
    securityProvider: SecurityProvider[IO]
  ): IO[Map[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]]] =
    (first to last).toList
      .foldLeftM((Hash.empty, Map.empty[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]])) {
        case ((parentHash, acc), value) =>
          val ordinal = SnapshotOrdinal.unsafeApply(value)
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
              .map(hashed => (hashed.hash, acc.updated(ordinal, hashed)))
          }
      }
      .map(_._2)

  test("rollback storage initialization uses the lead archive while every peer fetch fails") {
    SecurityProvider.forAsync[IO].use { implicit securityProvider =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        implicit0(hasherSelector: HasherSelector[IO]) = HasherSelector.forSyncAlwaysCurrent(hasher)
        implicit0(logger: Logger[IO]) = Slf4jLogger.getLogger[IO]
        keyPair <- KeyPairGenerator.makeKeyPair[IO]
        snapshots <- chain(98L, 100L, keyPair)
        archiveByHash <- Ref.of[IO, Map[Hash, Signed[GlobalIncrementalSnapshot]]](
          snapshots.valuesIterator.map(snapshot => snapshot.hash -> snapshot.signed).toMap
        )
        archiveHead <- Ref.of[IO, Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](None)
        peerFetches <- Ref.of[IO, Int](0)
        globalSnapshotStorage = new SnapshotStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {
          private def unused[A]: IO[A] = IO.raiseError(new IllegalStateException("unexpected storage operation"))

          def prepend(snapshot: Signed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo)(
            implicit hasher: Hasher[IO]
          ): IO[Boolean] = archiveHead.set((snapshot, state).some).as(true)
          def head: IO[Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = archiveHead.get
          def headSnapshot: IO[Option[Signed[GlobalIncrementalSnapshot]]] = archiveHead.get.map(_.map(_._1))
          def get(ordinal: SnapshotOrdinal): IO[Option[Signed[GlobalIncrementalSnapshot]]] =
            IO.raiseError(new IllegalStateException(s"ordinal lookup must not select rollback lineage: $ordinal"))
          def getHashed(ordinal: SnapshotOrdinal)(implicit hasher: Hasher[IO]): IO[Option[Hashed[GlobalIncrementalSnapshot]]] = unused
          def get(hash: Hash): IO[Option[Signed[GlobalIncrementalSnapshot]]] = archiveByHash.get.map(_.get(hash))
          def getHash(ordinal: SnapshotOrdinal)(implicit hasher: Hasher[IO]): IO[Option[Hash]] = unused
          def setHeadForRecovery(snapshot: Signed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo)(
            implicit hasher: Hasher[IO]
          ): IO[Unit] = unused
        }
        lastN <- LastNGlobalSnapshotStorage.make[IO](LastGlobalSnapshotsSyncConfig(NonNegLong(2L), PosInt(2)))
        lastHead <- Ref.of[IO, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](None)
        last = new LastSnapshotStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {
          def set(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): IO[Unit] = lastHead.set((snapshot, state).some)
          def setInitial(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): IO[Unit] =
            lastHead.set((snapshot, state).some)
          def setForRecovery(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): IO[Unit] =
            lastHead.set((snapshot, state).some)
          def clear: IO[Unit] = lastHead.set(none)
          def get: IO[Option[Hashed[GlobalIncrementalSnapshot]]] = lastHead.get.map(_.map(_._1))
          def getCombined: IO[Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = lastHead.get
          def getCombinedStream: Stream[IO, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] =
            Stream.eval(lastHead.get)
          def getOrdinal: IO[Option[SnapshotOrdinal]] = get.map(_.map(_.ordinal))
          def getHeight: IO[Option[Height]] = get.map(_.map(_.height))
        }
        download = new Download[IO, GlobalIncrementalSnapshot] {
          def download(implicit hasherSelector: HasherSelector[IO]): IO[Unit] = IO.unit
          def recoveryDownload(implicit hasherSelector: HasherSelector[IO]): IO[Unit] = IO.unit
          def fetchSnapshot(hash: Option[Hash], ordinal: SnapshotOrdinal)(
            implicit hasher: Hasher[IO]
          ): IO[Signed[GlobalIncrementalSnapshot]] =
            peerFetches.update(_ + 1) >> IO.raiseError(new IllegalStateException(s"peer unavailable for $ordinal/$hash"))
        }
        parent = snapshots(SnapshotOrdinal.unsafeApply(100L))
        _ <- StoragesInitializer.initializeStorages(globalSnapshotStorage, lastN, last, download, parent, info)
        fetchedFromPeers <- peerFetches.get
        retained <- lastN.getLastN
        initializedLast <- last.get
      } yield
        expect.same(0, fetchedFromPeers) &&
          expect.same(
            Set(SnapshotOrdinal.unsafeApply(98L), SnapshotOrdinal.unsafeApply(99L), SnapshotOrdinal.unsafeApply(100L)),
            retained.map(_.ordinal).toSet
          ) &&
          expect(initializedLast.exists(_.hash === parent.hash))
    }
  }
}
