package io.constellationnetwork.currency.l0.infrastructure.snapshot

import cats.effect.std.Supervisor
import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.snapshot.storage.ExactSnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage._
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.{CurrencyStateProofSelector, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed

import better.files.File
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.Path
import weaver.MutableIOSuite

object CurrencySnapshotCleanupStorageSuite extends MutableIOSuite {
  implicit val currencyStateProofSelector: CurrencyStateProofSelector = CurrencyStateProofSelector.instance

  type Res = (Supervisor[IO], KryoSerializer[IO], JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    supervisor <- Supervisor[IO]
    kryo <- KryoSerializer.forAsync[IO](nodeSharedKryoRegistrar)
    implicit0(json: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    security <- SecurityProvider.forAsync[IO]
  } yield (supervisor, kryo, json, Hasher.forJson[IO], security)

  test("canonical recovery removes indexed future state and installs the selected head while unindexed hashes remain inert") { res =>
    implicit val (supervisor, kryo, json, hasher, security) = res
    implicit val selector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(hasher)

    File.temporaryDirectory() { root =>
      def path(name: String): Path = Path((root / name).pathAsString)

      for {
        snapshots <- CurrencyIncrementalSnapshotLocalFileSystemStorage.make[IO](path("snapshots"))
        infos <- CurrencySnapshotInfoLocalFileSystemStorage.make[IO](path("info"))
        checkpoints <- CombinedSnapshotCheckpointFileSystemStorage.make[IO, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          path("checkpoints")
        )
        storage <- SnapshotStorage.make[IO, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          snapshots,
          infos,
          NonNegLong(5L),
          SnapshotOrdinal.MinValue,
          selector,
          checkpoints
        )
        cleanup = CurrencySnapshotCleanupStorage.make[IO](snapshots, infos)
        key <- KeyPairGenerator.makeKeyPair[IO]
        genesis <- Signed.forAsyncHasher[IO, CurrencySnapshot](CurrencySnapshot.mkGenesis(Map.empty, None, None), key)
        hashedGenesis <- genesis.toHashed[IO]
        base <- CurrencySnapshot.mkFirstIncrementalSnapshot[IO](hashedGenesis)
        info = genesis.value.info.toCurrencySnapshotInfo
        selected <- Signed.forAsyncHasher[IO, CurrencyIncrementalSnapshot](base, key)
        indexedConflict <- Signed
          .forAsyncHasher[IO, CurrencyIncrementalSnapshot](base.copy(epochProgress = EpochProgress(NonNegLong(1L))), key)
        unindexedConflict <- Signed
          .forAsyncHasher[IO, CurrencyIncrementalSnapshot](base.copy(epochProgress = EpochProgress(NonNegLong(2L))), key)
        future <- Signed.forAsyncHasher[IO, CurrencyIncrementalSnapshot](base.copy(ordinal = SnapshotOrdinal.unsafeApply(40001L)), key)
        orphan <- Signed.forAsyncHasher[IO, CurrencyIncrementalSnapshot](base.copy(ordinal = SnapshotOrdinal.unsafeApply(40002L)), key)
        selectedHash <- selected.toHashed[IO].map(_.hash)
        indexedHash <- indexedConflict.toHashed[IO].map(_.hash)
        unindexedHash <- unindexedConflict.toHashed[IO].map(_.hash)
        futureHash <- future.toHashed[IO].map(_.hash)
        orphanHash <- orphan.toHashed[IO].map(_.hash)
        _ <- snapshots.write(unindexedConflict) >> snapshots.delete(unindexedConflict.ordinal) >> snapshots.write(indexedConflict)
        _ <- storage.setHeadForRecoveryExact(future, info)
        _ <- snapshots.write(orphan) >> snapshots.delete(orphan.ordinal) >> infos.write(orphan.ordinal, info)
        installed <- ExactSnapshotStorage.installCanonicalSuffixForRecovery(
          storage,
          selected,
          info,
          cleanup.cleanupCanonicalSuffix(selected.ordinal, selectedHash) >> checkpoints.deleteAbove(selected.ordinal)
        )
        head <- storage.head
        futureByOrdinal <- storage.get(future.ordinal)
        futureByHash <- storage.get(futureHash)
        futureInfo <- infos.read(future.ordinal)
        orphanInfo <- infos.read(orphan.ordinal)
        futureIndexes <- snapshots.findAbove(selected.ordinal).compile.toList
        keptOrphan <- snapshots.read(orphanHash)
        keptConflict <- snapshots.read(unindexedHash)
        removedIndexedConflict <- snapshots.read(indexedHash)
        // A restart keeps the selected ordinal, exact envelope and checkpoint despite retained alternates.
        restarted <- CurrencyIncrementalSnapshotLocalFileSystemStorage.make[IO](path("snapshots"))
        restartedCheckpoints <- CombinedSnapshotCheckpointFileSystemStorage.make[IO, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          path("checkpoints")
        )
        coldOrdinal <- restarted.read(selected.ordinal)
        coldHash <- restarted.read(selectedHash)
        coldCheckpoint <- restartedCheckpoints.getLatestOrdinal
        coldCheckpointHash <- restartedCheckpoints.getCachedHash(selected.ordinal)
        conflictStatus <- restarted.ensureOrdinalLink(unindexedHash, selected.ordinal)
        afterConflict <- restarted.read(selected.ordinal)
      } yield
        expect.all(
          installed,
          head.contains((selected, info)),
          futureByOrdinal.isEmpty,
          futureByHash.isEmpty,
          futureInfo.isEmpty,
          orphanInfo.isEmpty,
          futureIndexes.isEmpty,
          keptOrphan.contains(orphan),
          keptConflict.contains(unindexedConflict),
          removedIndexedConflict.isEmpty,
          coldOrdinal.contains(selected),
          coldHash.contains(selected),
          coldCheckpoint.contains(selected.ordinal),
          coldCheckpointHash.contains(selectedHash),
          conflictStatus == SnapshotLocalFileSystemStorage.OrdinalLinkStatus.OrdinalOccupied(selected.ordinal, selectedHash),
          afterConflict.contains(selected)
        )
    }
  }
  test("ordinary Currency persistence repairs a retained torn hash before publishing its ordinal") { res =>
    implicit val (supervisor, kryo, json, hasher, security) = res
    implicit val selector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(hasher)

    File.temporaryDirectory() { root =>
      def path(name: String): Path = Path((root / name).pathAsString)

      for {
        snapshots <- CurrencyIncrementalSnapshotLocalFileSystemStorage.make[IO](path("snapshots"))
        infos <- CurrencySnapshotInfoLocalFileSystemStorage.make[IO](path("info"))
        checkpoints <- CombinedSnapshotCheckpointFileSystemStorage.make[IO, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          path("checkpoints")
        )
        storage <- SnapshotStorage.make[IO, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          snapshots,
          infos,
          NonNegLong(5L),
          SnapshotOrdinal.MinValue,
          selector,
          checkpoints
        )
        key <- KeyPairGenerator.makeKeyPair[IO]
        genesis <- Signed.forAsyncHasher[IO, CurrencySnapshot](CurrencySnapshot.mkGenesis(Map.empty, None, None), key)
        hashedGenesis <- genesis.toHashed[IO]
        base <- CurrencySnapshot.mkFirstIncrementalSnapshot[IO](hashedGenesis)
        snapshot <- Signed.forAsyncHasher[IO, CurrencyIncrementalSnapshot](base, key)
        hash <- snapshot.toHashed[IO].map(_.hash)
        info = genesis.value.info.toCurrencySnapshotInfo
        _ <- snapshots.write(snapshot) >> snapshots.delete(snapshot.ordinal)
        hashFile <- snapshots.getPath(hash)
        _ <- IO.blocking {
          val bytes = hashFile.byteArray
          hashFile.writeByteArray(bytes.take(bytes.length / 2))
        }
        before <- snapshots.ensureOrdinalLink(hash, snapshot.ordinal)
        // StateChannelSnapshotService.persist uses this ordinary enqueue/persistence boundary.
        installed <- ExactSnapshotStorage.prependExact(storage, snapshot, info)
        after <- snapshots.ensureOrdinalLink(hash, snapshot.ordinal)
        byOrdinal <- snapshots.read(snapshot.ordinal)
        byHash <- snapshots.read(hash)
        persistedInfo <- infos.read(snapshot.ordinal)
      } yield
        expect.all(
          before == SnapshotLocalFileSystemStorage.OrdinalLinkStatus.HashUnreadable,
          installed,
          after == SnapshotLocalFileSystemStorage.OrdinalLinkStatus.Linked,
          byOrdinal.contains(snapshot),
          byHash.contains(snapshot),
          persistedInfo.contains(info)
        )
    }
  }

}
