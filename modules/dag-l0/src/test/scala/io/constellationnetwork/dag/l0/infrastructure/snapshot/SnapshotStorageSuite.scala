package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.std.{Queue, Supervisor}
import cats.effect.{IO, Ref, Resource}
import cats.syntax.option._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage._
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.{GlobalStateProofSelector, SnapshotOrdinal, _}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import better.files._
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.concurrent.SignallingRef
import fs2.io.file.Path
import io.chrisdavenport.mapref.MapRef
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object SnapshotStorageSuite extends MutableIOSuite with Checkers {
  implicit val globalStateProofSelector: GlobalStateProofSelector = GlobalStateProofSelector(SnapshotOrdinal(NonNegLong(Long.MaxValue)))

  type Res = (Supervisor[IO], KryoSerializer[IO], JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    supervisor <- Supervisor[IO]
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](nodeSharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (supervisor, ks, j, h, sp)

  def mkStorage(tmpDir: File)(implicit K: KryoSerializer[IO], J: JsonSerializer[IO], H: Hasher[IO], S: Supervisor[IO]) =
    GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](Path(tmpDir.pathAsString)).flatMap { snapshotFileStorage =>
      GlobalSnapshotInfoLocalFileSystemStorage.make[IO](Path(tmpDir.pathAsString)).flatMap { snapshotInfoFileStorage =>
        CombinedSnapshotCheckpointFileSystemStorage
          .make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](Path(tmpDir.pathAsString))
          .flatMap { checkpointStorage =>
            implicit val hs = HasherSelector.forSyncAlwaysCurrent(H)
            io.constellationnetwork.node.shared.infrastructure.snapshot.storage.SnapshotStorage
              .make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
                snapshotFileStorage,
                snapshotInfoFileStorage,
                inMemoryCapacity = 5L,
                SnapshotOrdinal.MinValue,
                hs,
                checkpointStorage
              )
          }
      }
    }

  def mkStorageWithAcceptedHead(
    tmpDir: File,
    snapshot: Signed[GlobalIncrementalSnapshot],
    acceptedState: GlobalSnapshotInfo
  )(implicit K: KryoSerializer[IO], J: JsonSerializer[IO], H: Hasher[IO], S: Supervisor[IO]) = {
    // Models cancellation after the head CAS succeeds but before enqueue persists the accepted tuple.
    val snapshotsPath = Path((tmpDir / "snapshots").pathAsString)
    val snapshotInfoPath = Path((tmpDir / "snapshot-info").pathAsString)
    val checkpointsPath = Path((tmpDir / "checkpoints").pathAsString)

    for {
      headRef <- SignallingRef.of[IO, Option[(Signed[GlobalIncrementalSnapshot], Hasher[IO], GlobalSnapshotInfo)]](
        (snapshot, H, acceptedState).some
      )
      ordinalCache <- MapRef.ofSingleImmutableMap[IO, SnapshotOrdinal, Hash](Map.empty)
      hashCache <- MapRef.ofSingleImmutableMap[IO, Hash, Signed[GlobalIncrementalSnapshot]](Map.empty)
      notPersistedCache <- Ref.of[IO, Set[SnapshotOrdinal]](Set.empty)
      offloadQueue <- Queue.unbounded[IO, SnapshotOrdinal]
      snapshotInfoCutoffQueue <- Queue.unbounded[IO, SnapshotOrdinal]
      snapshotFileStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](snapshotsPath)
      snapshotInfoFileStorage <- GlobalSnapshotInfoLocalFileSystemStorage.make[IO](snapshotInfoPath)
      checkpointStorage <- CombinedSnapshotCheckpointFileSystemStorage
        .make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](checkpointsPath)
      storage <- {
        implicit val hs = HasherSelector.forSyncAlwaysCurrent(H)
        io.constellationnetwork.node.shared.infrastructure.snapshot.storage.SnapshotStorage
          .make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
            headRef,
            ordinalCache,
            hashCache,
            notPersistedCache,
            offloadQueue,
            snapshotInfoCutoffQueue,
            snapshotFileStorage,
            snapshotInfoFileStorage,
            inMemoryCapacity = 5L,
            SnapshotOrdinal.MinValue,
            hs,
            checkpointStorage,
            None
          )
      }
    } yield (storage, snapshotFileStorage, snapshotInfoFileStorage)
  }

  def mkStorageWithFailingFirstSnapshotInfoWrite(
    tmpDir: File
  )(implicit K: KryoSerializer[IO], J: JsonSerializer[IO], H: Hasher[IO], S: Supervisor[IO]) = {
    val snapshotsPath = Path((tmpDir / "snapshots").pathAsString)
    val snapshotInfoPath = Path((tmpDir / "snapshot-info").pathAsString)
    val checkpointsPath = Path((tmpDir / "checkpoints").pathAsString)

    for {
      failNextWrite <- Ref.of[IO, Boolean](true)
      snapshotFileStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](snapshotsPath)
      snapshotInfoFileStorage <- {
        val storage = new SnapshotInfoLocalFileSystemStorage[IO, GlobalSnapshotStateProof, GlobalSnapshotInfo](snapshotInfoPath) {
          def deserializeFallback(bytes: Array[Byte]): Either[Throwable, GlobalSnapshotInfo] =
            K.deserialize[GlobalSnapshotInfoV2](bytes).map(_.toGlobalSnapshotInfo)

          override def write(ordinal: SnapshotOrdinal, snapshotInfo: GlobalSnapshotInfo): IO[Unit] =
            failNextWrite
              .getAndSet(false)
              .ifM(
                IO.raiseError(new RuntimeException("injected snapshot-info write failure")),
                super.write(ordinal, snapshotInfo)
              )
        }

        storage.createDirectoryIfNotExists().rethrowT.as(storage)
      }
      checkpointStorage <- CombinedSnapshotCheckpointFileSystemStorage
        .make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](checkpointsPath)
      storage <- {
        implicit val hs = HasherSelector.forSyncAlwaysCurrent(H)
        io.constellationnetwork.node.shared.infrastructure.snapshot.storage.SnapshotStorage
          .make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
            snapshotFileStorage,
            snapshotInfoFileStorage,
            inMemoryCapacity = 5L,
            SnapshotOrdinal.MinValue,
            hs,
            checkpointStorage
          )
      }
    } yield (storage, snapshotFileStorage, snapshotInfoFileStorage)
  }

  def mkSnapshots(
    implicit H: Hasher[IO],
    S: SecurityProvider[IO],
    j: JsonSerializer[IO]
  ): IO[(Signed[GlobalSnapshot], Signed[GlobalIncrementalSnapshot])] =
    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      Signed.forAsyncHasher[IO, GlobalSnapshot](GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue), keyPair).flatMap { genesis =>
        GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](genesis).flatMap { snapshot =>
          Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](snapshot, keyPair).map((genesis, _))
        }
      }
    }

  test("head - returns none for empty storage") { res =>
    implicit val (s, kryo, j, h, _) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        storage.head.map {
          expect.eql(none, _)
        }
      }
    }
  }

  test("head - returns latest snapshot if not empty") { res =>
    implicit val (s, kryo, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info.toGlobalSnapshotInfo) >>
              storage.headSnapshot.map {
                expect.eql(snapshot.some, _)
              }
        }
      }
    }
  }

  test("prepend - should return true if next snapshot creates a chain") { res =>
    implicit val (s, kryo, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info.toGlobalSnapshotInfo).map(expect.eql(true, _))
        }
      }
    }
  }

  test("prepend - should allow to start from any arbitrary snapshot") { res =>
    implicit val (s, kryo, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info.toGlobalSnapshotInfo).map(expect.same(true, _))
        }
      }
    }
  }

  test("prepend - exact current replay repairs persistence with the accepted head context") { res =>
    implicit val (s, kryo, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkSnapshots.flatMap {
        case (genesis, snapshot) =>
          val acceptedState = genesis.info.toGlobalSnapshotInfo
          val replayState = acceptedState.copy(
            balances = SortedMap(
              address.Address("DAG2AUdecqFwEGcgAcH1ac2wrsg8acrgGwrQojzw") -> balance.Balance(100L)
            )
          )

          mkStorageWithAcceptedHead(tmpDir, snapshot, acceptedState).flatMap {
            case (storage, snapshotStorage, snapshotInfoStorage) =>
              for {
                snapshotBeforeReplay <- snapshotStorage.read(snapshot.ordinal)
                stateBeforeReplay <- snapshotInfoStorage.read(snapshot.ordinal)
                replay <- storage.prepend(snapshot, replayState)
                head <- storage.head
                persistedSnapshot <- snapshotStorage.read(snapshot.ordinal)
                persistedState <- snapshotInfoStorage.read(snapshot.ordinal)
              } yield
                expect
                  .eql(snapshotBeforeReplay, none)
                  .and(expect.eql(stateBeforeReplay, none))
                  .and(expect(replay))
                  .and(expect.eql(head, (snapshot, acceptedState).some))
                  .and(expect.eql(persistedSnapshot, snapshot.some))
                  .and(expect.eql(persistedState, acceptedState.some))
          }
      }
    }
  }

  test("prepend - snapshot-info failure is reported and exact-current retry repairs accepted persistence") { res =>
    implicit val (s, kryo, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkSnapshots.flatMap {
        case (genesis, snapshot) =>
          val acceptedState = genesis.info.toGlobalSnapshotInfo
          val replayState = acceptedState.copy(
            balances = SortedMap(
              address.Address("DAG2AUdecqFwEGcgAcH1ac2wrsg8acrgGwrQojzw") -> balance.Balance(100L)
            )
          )

          mkStorageWithFailingFirstSnapshotInfoWrite(tmpDir).flatMap {
            case (storage, snapshotStorage, snapshotInfoStorage) =>
              for {
                first <- storage.prepend(snapshot, acceptedState).attempt
                headAfterFailure <- storage.head
                persistedSnapshotAfterFailure <- snapshotStorage.read(snapshot.ordinal)
                persistedStateAfterFailure <- snapshotInfoStorage.read(snapshot.ordinal)
                retry <- storage.prepend(snapshot, replayState)
                headAfterRetry <- storage.head
                persistedStateAfterRetry <- snapshotInfoStorage.read(snapshot.ordinal)
              } yield
                expect(first.isLeft)
                  .and(expect.eql(headAfterFailure, (snapshot, acceptedState).some))
                  .and(expect.eql(persistedSnapshotAfterFailure, snapshot.some))
                  .and(expect.eql(persistedStateAfterFailure, none))
                  .and(expect(retry))
                  .and(expect.eql(headAfterRetry, (snapshot, acceptedState).some))
                  .and(expect.eql(persistedStateAfterRetry, acceptedState.some))
          }
      }
    }
  }

  test("last snapshot set - conflict failure preserves the accepted head for an exact retry") { res =>
    implicit val (_, _, j, h, sp) = res

    mkSnapshots.flatMap {
      case (genesis, snapshot) =>
        val acceptedState = genesis.info.toGlobalSnapshotInfo
        val rejectedState = acceptedState.copy(
          balances = SortedMap(
            address.Address("DAG2AUdecqFwEGcgAcH1ac2wrsg8acrgGwrQojzw") -> balance.Balance(100L)
          )
        )

        for {
          accepted <- snapshot.toHashed
          keyPair <- KeyPairGenerator.makeKeyPair[IO]
          conflictingSigned <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
            snapshot.value.copy(version = semver.SnapshotVersion("1.0.0")),
            keyPair
          )
          conflicting <- conflictingSigned.toHashed
          storage <- io.constellationnetwork.node.shared.infrastructure.snapshot.storage.LastSnapshotStorage
            .make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo]((accepted, acceptedState).some)
          conflict <- storage.set(conflicting, rejectedState).attempt
          combinedAfterConflict <- storage.getCombined
          retry <- storage.set(accepted, rejectedState).attempt
          combinedAfterRetry <- storage.getCombined
        } yield
          expect(conflict.isLeft)
            .and(expect.eql(combinedAfterConflict.map(_._1.hash), accepted.hash.some))
            .and(expect.eql(combinedAfterConflict.map(_._2.balances), acceptedState.balances.some))
            .and(expect(retry.isRight))
            .and(expect.eql(combinedAfterRetry.map(_._1.hash), accepted.hash.some))
            .and(expect.eql(combinedAfterRetry.map(_._2.balances), acceptedState.balances.some))
    }
  }

  test("prepend - conflicting value at the current ordinal still fails") { res =>
    implicit val (s, kryo, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            for {
              keyPair <- KeyPairGenerator.makeKeyPair[IO]
              conflicting <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
                snapshot.value.copy(version = semver.SnapshotVersion("1.0.0")),
                keyPair
              )
              first <- storage.prepend(snapshot, genesis.info.toGlobalSnapshotInfo)
              conflict <- storage.prepend(conflicting, genesis.info.toGlobalSnapshotInfo)
              head <- storage.headSnapshot
            } yield
              expect(first)
                .and(expect(!conflict))
                .and(expect.eql(head, snapshot.some))
        }
      }
    }
  }

  test("get - should return snapshot by ordinal") { res =>
    implicit val (s, kryo, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info.toGlobalSnapshotInfo) >>
              storage.get(snapshot.ordinal).map(expect.eql(snapshot.some, _))
        }
      }
    }
  }

  test("get - should return snapshot by hash") { res =>
    implicit val (s, kryo, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info.toGlobalSnapshotInfo) >>
              snapshot.value.hash.flatMap { hash =>
                storage.get(hash).map(expect.eql(snapshot.some, _))
              }
        }
      }
    }
  }

  test("getLatestBalancesStream - subscriber should get latest balances") { res =>
    implicit val (s, kryo, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info.toGlobalSnapshotInfo) >>
              storage.getLatestBalancesStream.take(1).compile.toList.map {
                expect.same(_, List(Map.empty[address.Address, balance.Balance]))
              }
        }
      }
    }
  }

  test("getLatestBalancesStream - second subscriber should get latest balances") { res =>
    implicit val (s, kryo, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info.toGlobalSnapshotInfo) >>
              storage.getLatestBalancesStream.take(1).compile.toList >>
              storage.getLatestBalancesStream.take(1).compile.toList.map {
                expect.same(_, List(Map.empty[address.Address, balance.Balance]))
              }
        }
      }
    }
  }
}
