package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.{IO, Resource}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage._
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed

import better.files.File
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.Path
import weaver.MutableIOSuite

object SnapshotDownloadStorageRecoverySuite extends MutableIOSuite {

  type Res = (KryoSerializer[IO], JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] = for {
    implicit0(kryoSerializer: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](nodeSharedKryoRegistrar)
    implicit0(jsonSerializer: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    securityProvider <- SecurityProvider.forAsync[IO]
    hasher = Hasher.forJson[IO]
  } yield (kryoSerializer, jsonSerializer, hasher, securityProvider)

  private val jsonHashSelect = new HashSelect {
    def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash
  }

  private final case class RecoveryStorages(
    download: io.constellationnetwork.dag.l0.domain.snapshot.storages.SnapshotDownloadStorage[IO],
    tmp: SnapshotLocalFileSystemStorage[IO, GlobalIncrementalSnapshot],
    persisted: SnapshotLocalFileSystemStorage[IO, GlobalIncrementalSnapshot],
    info: SnapshotInfoLocalFileSystemStorage[IO, GlobalSnapshotStateProof, GlobalSnapshotInfo]
  )

  private def makeStorages(root: File)(
    implicit kryoSerializer: KryoSerializer[IO],
    jsonSerializer: JsonSerializer[IO],
    hasherSelector: HasherSelector[IO]
  ): IO[RecoveryStorages] = {
    def path(name: String): Path = Path((root / name).pathAsString)

    for {
      tmp <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](path("tmp"))
      persisted <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](path("persisted"))
      full <- GlobalSnapshotLocalFileSystemStorage.make[IO](path("full"))
      info <- GlobalSnapshotInfoLocalFileSystemStorage.make[IO](path("info"))
      kryoInfo <- GlobalSnapshotInfoKryoLocalFileSystemStorage.make[IO](path("info"))
      download = SnapshotDownloadStorage.make[IO](tmp, persisted, full, info, kryoInfo, jsonHashSelect)
    } yield RecoveryStorages(download, tmp, persisted, info)
  }

  private def makeSnapshot(
    implicit hasher: Hasher[IO],
    securityProvider: SecurityProvider[IO]
  ): IO[Signed[GlobalIncrementalSnapshot]] =
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      genesis <- Signed.forAsyncHasher[IO, GlobalSnapshot](
        GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue),
        keyPair
      )
      incremental <- GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](genesis.value)
      snapshot <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](incremental, keyPair)
    } yield snapshot

  test("getHighestSnapshotInfoOrdinal skips a damaged highest file") { res =>
    implicit val (kryoSerializer, jsonSerializer, hasher, _) = res
    implicit val hasherSelector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(hasher)

    File.temporaryDirectory() { root =>
      val lower = SnapshotOrdinal.unsafeApply(1L)
      val higher = SnapshotOrdinal.unsafeApply(2L)

      for {
        storages <- makeStorages(root)
        _ <- storages.info.write(lower, GlobalSnapshotInfo.empty)
        _ <- storages.info.write(higher, GlobalSnapshotInfo.empty)
        _ <- IO.blocking((root / "info" / higher.value.value.toString).writeByteArray(Array[Byte](1, 2, 3)))
        selected <- storages.download.getHighestSnapshotInfoOrdinal(higher)
      } yield expect.same(Some(lower), selected)
    }
  }

  test("ensurePersistedAnchor repairs a missing ordinal index and rejects damaged snapshot-info") { res =>
    implicit val (kryoSerializer, jsonSerializer, hasher, securityProvider) = res
    implicit val hasherSelector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(hasher)

    File.temporaryDirectory() { root =>
      for {
        storages <- makeStorages(root)
        snapshot <- makeSnapshot
        hashed <- snapshot.toHashed[IO]
        _ <- storages.download.writePersisted(snapshot)
        _ <- storages.info.write(snapshot.ordinal, GlobalSnapshotInfo.empty)
        _ <- storages.persisted.delete(snapshot.ordinal)
        repaired <- storages.download.ensurePersistedAnchor(hashed.hash, snapshot.ordinal)
        byOrdinal <- storages.persisted.read(snapshot.ordinal)
        _ <- IO.blocking((root / "info" / snapshot.ordinal.value.value.toString).writeByteArray(Array[Byte](1, 2, 3)))
        rejected <- storages.download.ensurePersistedAnchor(hashed.hash, snapshot.ordinal)
      } yield expect.all(repaired, byOrdinal.contains(snapshot), !rejected)
    }
  }

  test("an unreadable persisted snapshot is rejected and replaced by a validated recovery envelope") { res =>
    implicit val (kryoSerializer, jsonSerializer, hasher, securityProvider) = res
    implicit val hasherSelector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(hasher)

    File.temporaryDirectory() { root =>
      for {
        storages <- makeStorages(root)
        snapshot <- makeSnapshot
        hashed <- snapshot.toHashed[IO]
        _ <- storages.download.writePersisted(snapshot)
        _ <- storages.info.write(snapshot.ordinal, GlobalSnapshotInfo.empty)
        hashPath <- storages.persisted.getPath(hashed.hash)
        _ <- IO.blocking(hashPath.writeByteArray(Array[Byte](1, 2, 3)))
        rejected <- storages.download.ensurePersistedAnchor(hashed.hash, snapshot.ordinal)
        _ <- storages.download.moveTmpToPersisted(snapshot)
        byHash <- storages.persisted.read(hashed.hash)
        byOrdinal <- storages.persisted.read(snapshot.ordinal)
      } yield expect.all(!rejected, byHash.contains(snapshot), byOrdinal.contains(snapshot))
    }
  }

  test("recovery promotion replaces both indexes and converges without a temporary copy after restart") { res =>
    implicit val (kryoSerializer, jsonSerializer, hasher, securityProvider) = res
    implicit val hasherSelector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(hasher)

    File.temporaryDirectory() { root =>
      for {
        storages <- makeStorages(root)
        original <- makeSnapshot
        replacementKey <- KeyPairGenerator.makeKeyPair[IO]
        replacement <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          original.value.copy(epochProgress = EpochProgress(NonNegLong.unsafeFrom(1L))),
          replacementKey
        )
        originalHashed <- original.toHashed[IO]
        replacementHashed <- replacement.toHashed[IO]
        _ <- storages.download.writePersisted(original)
        _ <- storages.tmp.writeUnderOrdinal(replacement)
        _ <- storages.tmp.delete(replacement.ordinal)
        _ <- storages.download.moveTmpToPersisted(replacement)
        restarted <- makeStorages(root)
        _ <- restarted.download.moveTmpToPersisted(replacement)
        oldByHash <- restarted.persisted.read(originalHashed.hash)
        replacementByHash <- restarted.persisted.read(replacementHashed.hash)
        replacementByOrdinal <- restarted.persisted.read(replacement.ordinal)
        temporary <- restarted.tmp.read(replacement.ordinal)
      } yield
        expect.all(
          originalHashed.hash != replacementHashed.hash,
          oldByHash.isEmpty,
          replacementByHash.contains(replacement),
          replacementByOrdinal.contains(replacement),
          temporary.isEmpty
        )
    }
  }
}
