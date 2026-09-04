package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect._
import cats.effect.std.{Mutex, Queue, Supervisor}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration._

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage._
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.{GlobalStateProofSelector, SnapshotOrdinal, _}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops.PublicKeyOps
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

  def sharedResource: cats.effect.Resource[IO, Res] = for {
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

  def mkInspectableStorage(
    tmpDir: File,
    persistenceMutex: Mutex[IO],
    protectedSnapshotInfoOrdinals: Set[SnapshotOrdinal] = Set.empty
  )(implicit K: KryoSerializer[IO], J: JsonSerializer[IO], H: Hasher[IO], S: Supervisor[IO]) = {
    val snapshotsPath = Path((tmpDir / "snapshots").pathAsString)
    val snapshotInfoPath = Path((tmpDir / "snapshot-info").pathAsString)
    val checkpointsPath = Path((tmpDir / "checkpoints").pathAsString)

    for {
      headRef <- SignallingRef.of[IO, Option[(Signed[GlobalIncrementalSnapshot], Hasher[IO], GlobalSnapshotInfo)]](none)
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
            persistenceMutex,
            snapshotFileStorage,
            snapshotInfoFileStorage,
            inMemoryCapacity = 5L,
            SnapshotOrdinal.MinValue,
            hs,
            checkpointStorage,
            protectedSnapshotInfoOrdinals
          )
      }
    } yield
      (
        storage,
        snapshotFileStorage,
        snapshotInfoFileStorage,
        checkpointStorage,
        headRef,
        ordinalCache,
        hashCache,
        notPersistedCache
      )
  }

  def mkSeparatedStorage(
    tmpDir: File,
    persistenceMutex: Mutex[IO],
    protectedSnapshotInfoOrdinals: Set[SnapshotOrdinal] = Set.empty
  )(implicit K: KryoSerializer[IO], J: JsonSerializer[IO], H: Hasher[IO], S: Supervisor[IO]) =
    mkInspectableStorage(tmpDir, persistenceMutex, protectedSnapshotInfoOrdinals).map {
      case (storage, snapshotFileStorage, snapshotInfoFileStorage, checkpointStorage, _, _, _, _) =>
        (storage, snapshotFileStorage, snapshotInfoFileStorage, checkpointStorage)
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
      persistenceMutex <- Mutex[IO]
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
            persistenceMutex,
            snapshotFileStorage,
            snapshotInfoFileStorage,
            inMemoryCapacity = 5L,
            SnapshotOrdinal.MinValue,
            hs,
            checkpointStorage,
            Set.empty[SnapshotOrdinal]
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

  test("live snapshot cutoff retains an explicitly protected certified activation parent") { res =>
    implicit val (supervisor, kryo, json, hasher, securityProvider) = res

    def awaitDeleted(
      storage: SnapshotInfoLocalFileSystemStorage[IO, GlobalSnapshotStateProof, GlobalSnapshotInfo],
      ordinal: SnapshotOrdinal
    ): IO[Unit] =
      storage.exists(ordinal).flatMap {
        case false => IO.unit
        case true  => IO.sleep(20.millis) >> awaitDeleted(storage, ordinal)
      }

    File.temporaryDirectory() { tmpDir =>
      for {
        persistenceMutex <- Mutex[IO]
        protectedOrdinal = SnapshotOrdinal.unsafeApply(123L)
        ordinaryOldOrdinal = SnapshotOrdinal.unsafeApply(124L)
        currentOrdinal = SnapshotOrdinal.unsafeApply(1000L)
        built <- mkSeparatedStorage(tmpDir, persistenceMutex, Set(protectedOrdinal))
        (storage, _, snapshotInfoStorage, _) = built
        pair <- KeyPairGenerator.makeKeyPair[IO]
        (_, base) <- mkSnapshots
        current <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          base.value.copy(ordinal = currentOrdinal),
          pair
        )
        info = GlobalSnapshotInfo.empty
        _ <- snapshotInfoStorage.write(protectedOrdinal, info)
        _ <- snapshotInfoStorage.write(ordinaryOldOrdinal, info)
        accepted <- storage.prepend(current, info)
        _ <- awaitDeleted(snapshotInfoStorage, ordinaryOldOrdinal).timeout(5.seconds)
        protectedExists <- snapshotInfoStorage.exists(protectedOrdinal)
        currentExists <- snapshotInfoStorage.exists(currentOrdinal)
      } yield expect.all(accepted, protectedExists, currentExists)
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

  test("validated head publication exposes an externally installed suffix hidden by a cached miss") { res =>
    implicit val (supervisor, kryo, json, hasher, securityProvider) = res

    File.temporaryDirectory() { tmpDir =>
      for {
        mutex <- Mutex[IO]
        built <- mkInspectableStorage(tmpDir, mutex)
        (storage, snapshotFiles, _, _, _, _, _, _) = built
        (genesis, historical) <- mkSnapshots
        historicalHash <- historical.value.hash
        keyPair <- KeyPairGenerator.makeKeyPair[IO]
        terminal <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          historical.value.copy(
            ordinal = historical.ordinal.next,
            lastSnapshotHash = historicalHash
          ),
          keyPair
        )
        context = genesis.info.toGlobalSnapshotInfo
        missingBeforeInstall <- storage.get(historical.ordinal)
        _ <- snapshotFiles.write(historical)
        hiddenBeforePublication <- storage.get(historical.ordinal)
        _ <- storage.setHeadForRecovery(terminal, context)
        visibleAfterPublication <- storage.get(historical.ordinal)
        publishedHead <- storage.head
      } yield
        expect.all(
          missingBeforeInstall.isEmpty,
          hiddenBeforePublication.isEmpty,
          visibleAfterPublication.contains(historical),
          publishedHead.contains((terminal, context))
        )
    }
  }

  test("validated head publication compares metagraph data-application context by byte content") { res =>
    implicit val (supervisor, kryo, json, hasher, securityProvider) = res

    File.temporaryDirectory() { tmpDir =>
      for {
        mutex <- Mutex[IO]
        built <- mkInspectableStorage(tmpDir, mutex)
        (storage, _, snapshotInfoFiles, _, _, _, _, _) = built
        (genesis, target) <- mkSnapshots
        currencyKeyPair <- KeyPairGenerator.makeKeyPair[IO]
        currencySnapshot <- Signed.forAsyncHasher[IO, CurrencyIncrementalSnapshot](
          CurrencyIncrementalSnapshot(
            SnapshotOrdinal.MinIncrementalValue,
            Height.MinValue,
            SubHeight.MinValue,
            Hash.empty,
            SortedSet.empty,
            SortedSet.empty,
            SnapshotTips(SortedSet.empty, SortedSet.empty),
            CurrencySnapshotStateProof(Hash.empty, Hash.empty, None, None, None, None, None, None, None),
            EpochProgress.MinValue,
            DataApplicationPart(Array[Byte](1, 2, 3), List(Array[Byte](4, 5, 6)), Hash.empty, None).some,
            None,
            None,
            None,
            None,
            None,
            None,
            None
          ),
          currencyKeyPair
        )
        currencyInfo = CurrencySnapshotInfo(SortedMap.empty, SortedMap.empty, None, None, None, None, None, None, None)
        currencyEntry: Either[Signed[CurrencySnapshot], (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)] =
          Right((currencySnapshot, currencyInfo))
        context = genesis.info.toGlobalSnapshotInfo.copy(
          lastCurrencySnapshots = SortedMap(currencyKeyPair.getPublic.toAddress -> currencyEntry)
        )
        _ <- storage.setHeadForRecovery(target, context)
        persistedContext <- snapshotInfoFiles.read(target.ordinal)
        publishedHead <- storage.head
      } yield
        expect.all(
          persistedContext.exists(_ === context),
          publishedHead.exists { case (snapshot, state) => snapshot === target && state === context }
        )
    }
  }

  test("validated head publication leaves the visible head unchanged when snapshot-info persistence fails") { res =>
    implicit val (supervisor, kryo, json, hasher, securityProvider) = res

    File.temporaryDirectory() { tmpDir =>
      for {
        built <- mkStorageWithFailingFirstSnapshotInfoWrite(tmpDir)
        (storage, _, snapshotInfoFiles) = built
        (genesis, snapshot) <- mkSnapshots
        context = genesis.info.toGlobalSnapshotInfo
        first <- storage.setHeadForRecovery(snapshot, context).attempt
        headAfterFailure <- storage.head
        contextAfterFailure <- snapshotInfoFiles.read(snapshot.ordinal)
        _ <- storage.setHeadForRecovery(snapshot, context)
        headAfterRetry <- storage.head
      } yield
        expect.all(
          first.isLeft,
          headAfterFailure.isEmpty,
          contextAfterFailure.isEmpty,
          headAfterRetry.contains((snapshot, context))
        )
    }
  }

  test("validated head publication rejects a different value occupying the target ordinal without changing the head") { res =>
    implicit val (supervisor, kryo, json, hasher, securityProvider) = res

    File.temporaryDirectory() { tmpDir =>
      for {
        mutex <- Mutex[IO]
        built <- mkInspectableStorage(tmpDir, mutex)
        (storage, _, snapshotInfoFiles, _, _, _, _, _) = built
        (genesis, original) <- mkSnapshots
        originalContext = genesis.info.toGlobalSnapshotInfo
        conflictingContext = originalContext.copy(
          balances = SortedMap(
            address.Address("DAG2AUdecqFwEGcgAcH1ac2wrsg8acrgGwrQojzw") -> balance.Balance(100L)
          )
        )
        keyPair <- KeyPairGenerator.makeKeyPair[IO]
        conflicting <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          original.value.copy(version = semver.SnapshotVersion("1.0.0")),
          keyPair
        )
        _ <- storage.prepend(original, originalContext)
        publication <- storage.setHeadForRecovery(conflicting, conflictingContext).attempt
        headAfterFailure <- storage.head
        cachedAtTarget <- storage.get(original.ordinal)
        contextAfterFailure <- snapshotInfoFiles.read(original.ordinal)
      } yield
        expect.all(
          publication.isLeft,
          headAfterFailure.contains((original, originalContext)),
          cachedAtTarget.contains(original),
          contextAfterFailure.contains(originalContext)
        )
    }
  }

  test("validated lower-head publication evicts a deleted future suffix from positive caches") { res =>
    implicit val (supervisor, kryo, json, hasher, securityProvider) = res

    File.temporaryDirectory() { tmpDir =>
      for {
        mutex <- Mutex[IO]
        built <- mkInspectableStorage(tmpDir, mutex)
        (storage, snapshotFiles, _, _, _, _, _, _) = built
        (genesis, original) <- mkSnapshots
        context = genesis.info.toGlobalSnapshotInfo
        originalHash <- original.value.hash
        keyPair <- KeyPairGenerator.makeKeyPair[IO]
        future <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          original.value.copy(
            ordinal = original.ordinal.next,
            lastSnapshotHash = originalHash
          ),
          keyPair
        )
        futureHash <- future.value.hash
        _ <- storage.prepend(original, context)
        _ <- storage.prepend(future, context)
        _ <- snapshotFiles.delete(future.ordinal)
        _ <- snapshotFiles.delete(futureHash)
        cachedBeforePublication <- storage.get(future.ordinal)
        _ <- storage.setHeadForRecovery(original, context)
        byOrdinalAfterPublication <- storage.get(future.ordinal)
        byHashAfterPublication <- storage.get(futureHash)
        headAfterPublication <- storage.head
      } yield
        expect.all(
          cachedBeforePublication.contains(future),
          byOrdinalAfterPublication.isEmpty,
          byHashAfterPublication.isEmpty,
          headAfterPublication.contains((original, context))
        )
    }
  }

  test("validated head retry clears a stale not-persisted marker only after exact disk readback succeeds") { res =>
    implicit val (supervisor, kryo, json, hasher, securityProvider) = res

    File.temporaryDirectory() { tmpDir =>
      for {
        mutex <- Mutex[IO]
        built <- mkInspectableStorage(tmpDir, mutex)
        (storage, snapshotFiles, _, _, _, _, _, notPersisted) = built
        (genesis, target) <- mkSnapshots
        context = genesis.info.toGlobalSnapshotInfo
        keyPair <- KeyPairGenerator.makeKeyPair[IO]
        occupying <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          target.value.copy(version = semver.SnapshotVersion("1.0.0")),
          keyPair
        )
        occupyingHash <- occupying.value.hash
        _ <- snapshotFiles.write(occupying)
        _ <- notPersisted.update(_ + target.ordinal)
        failed <- storage.setHeadForRecovery(target, context).attempt
        markerAfterFailure <- notPersisted.get
        headAfterFailure <- storage.head
        _ <- snapshotFiles.delete(occupying.ordinal)
        _ <- snapshotFiles.delete(occupyingHash)
        _ <- storage.setHeadForRecovery(target, context)
        markerAfterRetry <- notPersisted.get
        headAfterRetry <- storage.head
      } yield
        expect.all(
          failed.isLeft,
          markerAfterFailure.contains(target.ordinal),
          headAfterFailure.isEmpty,
          !markerAfterRetry.contains(target.ordinal),
          headAfterRetry.contains((target, context))
        )
    }
  }

  test("exact recovery replaces same-value randomized proofs durably without ordinary prepend") { res =>
    implicit val (s, kryo, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      for {
        mutex <- Mutex[IO]
        built <- mkSeparatedStorage(tmpDir, mutex)
        (storage, _, _, _) = built
        snapshots <- mkSnapshots
        (genesis, original) = snapshots
        context = genesis.info.toGlobalSnapshotInfo
        replacementKey <- KeyPairGenerator.makeKeyPair[IO]
        replacement <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](original.value, replacementKey)
        valueHash <- original.value.hash
        _ <- storage.prepend(original, context)
        installed <- io.constellationnetwork.node.shared.domain.snapshot.storage.ExactSnapshotStorage
          .installExactForRecovery(storage, replacement, context)
        warmHead <- storage.head
        warmOrdinal <- storage.get(replacement.ordinal)
        warmHash <- storage.get(valueHash)
        coldMutex <- Mutex[IO]
        coldBuilt <- mkSeparatedStorage(tmpDir, coldMutex)
        (coldStorage, _, _, _) = coldBuilt
        coldOrdinal <- coldStorage.get(replacement.ordinal)
        coldHash <- coldStorage.get(valueHash)
      } yield
        expect.all(
          original.proofs != replacement.proofs,
          installed,
          warmHead.exists(_._1.proofs == replacement.proofs),
          warmOrdinal.exists(_.proofs == replacement.proofs),
          warmHash.exists(_.proofs == replacement.proofs),
          coldOrdinal.exists(_.proofs == replacement.proofs),
          coldHash.exists(_.proofs == replacement.proofs)
        )
    }
  }

  test("exact recovery evicts a cleaned future suffix and removes the abandoned anchor hash") { res =>
    implicit val (s, kryo, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      for {
        mutex <- Mutex[IO]
        built <- mkSeparatedStorage(tmpDir, mutex)
        (storage, snapshotFiles, snapshotInfoFiles, checkpoints) = built
        snapshots <- mkSnapshots
        (genesis, original) = snapshots
        context = genesis.info.toGlobalSnapshotInfo
        originalHash <- original.value.hash
        futureKey <- KeyPairGenerator.makeKeyPair[IO]
        future <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          original.value.copy(
            ordinal = original.ordinal.next,
            lastSnapshotHash = originalHash
          ),
          futureKey
        )
        futureHash <- future.value.hash
        replacementKey <- KeyPairGenerator.makeKeyPair[IO]
        replacement <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          original.value.copy(version = semver.SnapshotVersion("1.0.0")),
          replacementKey
        )
        replacementHash <- replacement.value.hash
        _ <- storage.prepend(original, context)
        _ <- storage.prepend(future, context)
        installed <- io.constellationnetwork.node.shared.domain.snapshot.storage.ExactSnapshotStorage
          .installCanonicalSuffixForRecovery(
            storage,
            replacement,
            context,
            snapshotFiles.delete(futureHash) >>
              snapshotFiles.delete(future.ordinal) >>
              snapshotInfoFiles.deleteAbove(original.ordinal) >>
              checkpoints.deleteAbove(original.ordinal)
          )
        warmFutureOrdinal <- storage.get(future.ordinal)
        warmFutureHash <- storage.get(futureHash)
        warmOldAnchor <- storage.get(originalHash)
        warmReplacement <- storage.get(replacementHash)
        coldMutex <- Mutex[IO]
        coldBuilt <- mkSeparatedStorage(tmpDir, coldMutex)
        (coldStorage, _, _, _) = coldBuilt
        coldFutureOrdinal <- coldStorage.get(future.ordinal)
        coldFutureHash <- coldStorage.get(futureHash)
        coldOldAnchor <- coldStorage.get(originalHash)
        coldReplacement <- coldStorage.get(replacementHash)
      } yield
        expect.all(
          installed,
          originalHash != replacementHash,
          warmFutureOrdinal.isEmpty,
          warmFutureHash.isEmpty,
          warmOldAnchor.isEmpty,
          warmReplacement.exists(_.value == replacement.value),
          coldFutureOrdinal.isEmpty,
          coldFutureHash.isEmpty,
          coldOldAnchor.isEmpty,
          coldReplacement.exists(_.value == replacement.value)
        )
    }
  }

  test("exact recovery waits for the shared persistence critical section") { res =>
    implicit val (s, kryo, j, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      for {
        mutex <- Mutex[IO]
        built <- mkSeparatedStorage(tmpDir, mutex)
        (storage, _, _, _) = built
        snapshots <- mkSnapshots
        (genesis, original) = snapshots
        context = genesis.info.toGlobalSnapshotInfo
        replacementKey <- KeyPairGenerator.makeKeyPair[IO]
        replacement <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](original.value, replacementKey)
        _ <- storage.prepend(original, context)
        cleanupRuns <- Ref.of[IO, Int](0)
        finished <- Deferred[IO, Unit]
        held <- mutex.lock.allocated
        (_, release) = held
        fiber <- io.constellationnetwork.node.shared.domain.snapshot.storage.ExactSnapshotStorage
          .installCanonicalSuffixForRecovery(storage, replacement, context, cleanupRuns.update(_ + 1))
          .flatTap(_ => finished.complete(()))
          .start
        _ <- IO.cede.replicateA_(8)
        whileHeld <- finished.tryGet
        cleanupWhileHeld <- cleanupRuns.get
        _ <- release
        installed <- fiber.joinWithNever
        head <- storage.head
        cleanupAfter <- cleanupRuns.get
      } yield
        expect.all(
          whileHeld.isEmpty,
          cleanupWhileHeld === 0,
          cleanupAfter === 1,
          installed,
          head.exists(_._1.proofs == replacement.proofs)
        )
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
