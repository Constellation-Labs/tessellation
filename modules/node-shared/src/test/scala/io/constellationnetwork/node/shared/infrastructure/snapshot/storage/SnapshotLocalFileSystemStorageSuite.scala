package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import java.io.IOException

import cats.effect.std.Supervisor
import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.shared.sharedKryoRegistrar
import io.constellationnetwork.storage.PathGenerator
import io.constellationnetwork.storage.PathGenerator._

import better.files._
import eu.timepit.refined.auto._
import fs2.io.file.Path
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

import SnapshotLocalFileSystemStorage.{OrdinalLinkStatus, UnableToPersistSnapshot}

object SnapshotLocalFileSystemStorageSuite extends MutableIOSuite with Checkers {

  val hashPathGenerator = PathGenerator.forHash(Depth(2), PrefixSize(3))
  val ordinalPathGenerator = PathGenerator.forOrdinal(ChunkSize(20000))

  type Res = (Supervisor[IO], KryoSerializer[IO], JsonSerializer[IO], Hasher[IO], SecurityProvider[IO], GlobalStateProofSelector)

  override def sharedResource: Resource[IO, Res] =
    for {
      s <- Supervisor[IO]
      implicit0(k: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar.union(nodeSharedKryoRegistrar))
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
      h = Hasher.forJson[IO]
      sp <- SecurityProvider.forAsync[IO]
      gsps = GlobalStateProofSelector(SnapshotOrdinal(Long.MaxValue))
    } yield (s, k, j, h, sp, gsps)

  private def mkLocalFileSystemStorage(tmpDir: File)(implicit K: KryoSerializer[IO], J: JsonSerializer[IO], H: Hasher[IO]) =
    GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](Path(tmpDir.pathAsString))

  private def mkReadFailingStorage(tmpDir: File, failedFileName: String)(
    implicit K: KryoSerializer[IO],
    J: JsonSerializer[IO]
  ): IO[SnapshotLocalFileSystemStorage[IO, GlobalIncrementalSnapshot]] = {
    val storage = new SnapshotLocalFileSystemStorage[IO, GlobalIncrementalSnapshot](Path(tmpDir.pathAsString)) {
      def deserializeFallback(bytes: Array[Byte]): Either[Throwable, Signed[GlobalIncrementalSnapshot]] =
        KryoSerializer[IO]
          .deserialize[Signed[GlobalIncrementalSnapshotV1]](bytes)
          .map(_.map(_.toGlobalIncrementalSnapshot))

      override def readBytes(fileName: String): IO[Option[Array[Byte]]] =
        if (fileName === failedFileName) IO.raiseError(new IOException("simulated snapshot-index I/O failure"))
        else super.readBytes(fileName)
    }

    storage.createDirectoryIfNotExists().rethrowT.as(storage)
  }

  private def mkSnapshots(
    implicit H: Hasher[IO],
    S: SecurityProvider[IO],
    gsps: GlobalStateProofSelector,
    js: JsonSerializer[IO]
  ): IO[(Signed[GlobalSnapshot], Signed[GlobalIncrementalSnapshot])] =
    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      Signed.forAsyncHasher[IO, GlobalSnapshot](GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue), keyPair).flatMap { genesis =>
        GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](genesis).flatMap { snapshot =>
          Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](snapshot, keyPair).map((genesis, _))
        }
      }
    }

  private def mkError(snapshot: Signed[GlobalIncrementalSnapshot], hashFileExists: Boolean)(
    implicit H: Hasher[IO]
  ): IO[UnableToPersistSnapshot] =
    snapshot.toHashed.map(hashed =>
      UnableToPersistSnapshot(
        "ordinal/" + ordinalPathGenerator.get(hashed.ordinal.value.toString),
        "hash/" + hashPathGenerator.get(hashed.hash.value),
        hashFileExists = hashFileExists
      )
    )

  private def mkHashFile(tmpDir: File, snapshot: Signed[GlobalIncrementalSnapshot])(
    implicit H: Hasher[IO]
  ): IO[File] =
    snapshot.toHashed.map(hashed => tmpDir / "hash" / hashPathGenerator.get(hashed.hash.value))

  private def mkHashFile(tmpDir: File, hash: io.constellationnetwork.security.hash.Hash): File =
    tmpDir / "hash" / hashPathGenerator.get(hash.value)

  private def mkOrdinalFile(tmpDir: File, snapshot: Signed[GlobalIncrementalSnapshot]): File =
    tmpDir / "ordinal" / ordinalPathGenerator.get(snapshot.ordinal.value.value.toString)

  test("write - fail if ordinal file and hash file already exist") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res

    File.temporaryDirectory() { tmpDir =>
      mkLocalFileSystemStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (_, snapshot) =>
            for {
              expectedError <- mkError(snapshot, hashFileExists = true)
              _ <- storage.write(snapshot)
              result <- storage.write(snapshot).attempt.map(_.swap)
            } yield expect.all(result.contains(expectedError))
        }
      }
    }
  }

  test("write - fail if ordinal file already exists but hash file does not") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res

    File.temporaryDirectory() { tmpDir =>
      mkLocalFileSystemStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (_, snapshot) =>
            for {
              expectedError <- mkError(snapshot, hashFileExists = false)
              _ <- storage.write(snapshot)
              _ <- mkHashFile(tmpDir, snapshot).map(_.delete())
              result <- storage.write(snapshot).attempt.map(_.swap)
            } yield expect.all(result.contains(expectedError))
        }
      }
    }
  }

  test("write - link ordinal file if missing but hash file exists") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res

    File.temporaryDirectory() { tmpDir =>
      mkLocalFileSystemStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (_, snapshot) =>
            for {
              _ <- storage.write(snapshot)

              ordinalFile = mkOrdinalFile(tmpDir, snapshot)
              notExistsBefore = ordinalFile.delete().notExists

              _ <- storage.write(snapshot)

              hashFile <- mkHashFile(tmpDir, snapshot)

            } yield expect.all(notExistsBefore, ordinalFile.isSameFileAs(hashFile))
        }
      }
    }
  }

  test("write - create hash file and link ordinal file to it") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res

    File.temporaryDirectory() { tmpDir =>
      mkLocalFileSystemStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (_, snapshot) =>
            for {
              _ <- storage.write(snapshot)

              hashFile <- mkHashFile(tmpDir, snapshot)
              ordinalFile = mkOrdinalFile(tmpDir, snapshot)

            } yield expect.all(hashFile.exists, ordinalFile.isSameFileAs(hashFile))
        }
      }
    }
  }

  test("replaceForRecovery - replaces randomized proof bytes at the same value hash across restart") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res

    File.temporaryDirectory() { tmpDir =>
      for {
        storage <- mkLocalFileSystemStorage(tmpDir)
        snapshots <- mkSnapshots
        (_, original) = snapshots
        replacementKey <- KeyPairGenerator.makeKeyPair[IO]
        replacement <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](original.value, replacementKey)
        valueHash <- original.value.hash
        _ <- storage.write(original)
        _ <- storage.replaceForRecovery(replacement)
        byOrdinal <- storage.read(replacement.ordinal)
        byHash <- storage.read(valueHash)
        restarted <- mkLocalFileSystemStorage(tmpDir)
        coldByOrdinal <- restarted.read(replacement.ordinal)
        coldByHash <- restarted.read(valueHash)
      } yield
        expect.all(
          original.proofs != replacement.proofs,
          byOrdinal.exists(_.proofs == replacement.proofs),
          byHash.exists(_.proofs == replacement.proofs),
          coldByOrdinal.exists(_.proofs == replacement.proofs),
          coldByHash.exists(_.proofs == replacement.proofs)
        )
    }
  }

  test("replaceForRecovery - removes an abandoned same-ordinal value hash across restart") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res

    File.temporaryDirectory() { tmpDir =>
      for {
        storage <- mkLocalFileSystemStorage(tmpDir)
        snapshots <- mkSnapshots
        (_, original) = snapshots
        replacementKey <- KeyPairGenerator.makeKeyPair[IO]
        replacement <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          original.value.copy(version = semver.SnapshotVersion("1.0.0")),
          replacementKey
        )
        originalHash <- original.value.hash
        replacementHash <- replacement.value.hash
        _ <- storage.write(original)
        _ <- storage.replaceForRecovery(replacement)
        warmOld <- storage.read(originalHash)
        warmNew <- storage.read(replacementHash)
        warmOrdinal <- storage.read(replacement.ordinal)
        restarted <- mkLocalFileSystemStorage(tmpDir)
        coldOld <- restarted.read(originalHash)
        coldNew <- restarted.read(replacementHash)
        coldOrdinal <- restarted.read(replacement.ordinal)
      } yield
        expect.all(
          originalHash != replacementHash,
          warmOld.isEmpty,
          coldOld.isEmpty,
          warmNew.exists(_.value == replacement.value),
          warmOrdinal.exists(_.value == replacement.value),
          coldNew.exists(_.value == replacement.value),
          coldOrdinal.exists(_.value == replacement.value)
        )
    }
  }

  test("canonical recovery removes every alternate hash at the anchor ordinal across restart") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res
    implicit val selector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(h)

    File.temporaryDirectory() { tmpDir =>
      for {
        storage <- mkLocalFileSystemStorage(tmpDir)
        snapshots <- mkSnapshots
        (_, base) = snapshots
        key1 <- KeyPairGenerator.makeKeyPair[IO]
        key2 <- KeyPairGenerator.makeKeyPair[IO]
        selectedKey <- KeyPairGenerator.makeKeyPair[IO]
        alternate1 <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          base.value.copy(epochProgress = EpochProgress(1L)),
          key1
        )
        alternate2 <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          base.value.copy(epochProgress = EpochProgress(2L)),
          key2
        )
        selected <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          base.value.copy(epochProgress = EpochProgress(3L)),
          selectedKey
        )
        alternate1Hash <- alternate1.value.hash
        alternate2Hash <- alternate2.value.hash
        selectedHash <- selected.value.hash
        alternate1Bytes <- JsonSerializer[IO].serialize(alternate1)
        alternate2Bytes <- JsonSerializer[IO].serialize(alternate2)
        _ <- storage.write(base)
        _ <- IO.blocking {
          val alternate1File = mkHashFile(tmpDir, alternate1Hash)
          val alternate2File = mkHashFile(tmpDir, alternate2Hash)
          alternate1File.parent.createDirectories()
          alternate2File.parent.createDirectories()
          alternate1File.writeByteArray(alternate1Bytes)
          alternate2File.writeByteArray(alternate2Bytes)
        }
        _ <- storage.cleanupCanonicalSuffix(
          selected.ordinal,
          selectedHash,
          (hash, ordinal) => storage.delete(hash) >> storage.delete(ordinal)
        )
        _ <- storage.replaceForRecovery(selected)
        warmAlternate1 <- storage.read(alternate1Hash)
        warmAlternate2 <- storage.read(alternate2Hash)
        warmSelected <- storage.read(selectedHash)
        warmOrdinal <- storage.read(selected.ordinal)
        restarted <- mkLocalFileSystemStorage(tmpDir)
        coldAlternate1 <- restarted.read(alternate1Hash)
        coldAlternate2 <- restarted.read(alternate2Hash)
        coldSelected <- restarted.read(selectedHash)
        coldOrdinal <- restarted.read(selected.ordinal)
      } yield
        expect.all(
          alternate1Hash != alternate2Hash,
          alternate1Hash != selectedHash,
          alternate2Hash != selectedHash,
          warmAlternate1.isEmpty,
          warmAlternate2.isEmpty,
          coldAlternate1.isEmpty,
          coldAlternate2.isEmpty,
          warmSelected.contains(selected),
          warmOrdinal.contains(selected),
          coldSelected.contains(selected),
          coldOrdinal.contains(selected)
        )
    }
  }

  test("canonical cleanup removes a linked conflicting anchor before filtering linked history") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res
    implicit val selector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(h)

    File.temporaryDirectory() { tmpDir =>
      for {
        storage <- mkLocalFileSystemStorage(tmpDir)
        snapshots <- mkSnapshots
        (_, localAnchor) = snapshots
        selectedKey <- KeyPairGenerator.makeKeyPair[IO]
        selectedAnchor <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          localAnchor.value.copy(epochProgress = EpochProgress(11L)),
          selectedKey
        )
        localHash <- localAnchor.value.hash
        selectedHash <- selectedAnchor.value.hash
        selectedBytes <- JsonSerializer[IO].serialize(selectedAnchor)
        _ <- storage.write(localAnchor)
        _ <- IO.blocking {
          val selectedFile = mkHashFile(tmpDir, selectedHash)
          selectedFile.parent.createDirectories()
          selectedFile.writeByteArray(selectedBytes)
        }
        _ <- storage.cleanupCanonicalSuffix(
          localAnchor.ordinal,
          selectedHash,
          (hash, ordinal) => storage.delete(hash) >> storage.delete(ordinal)
        )
        localByHash <- storage.read(localHash)
        localByOrdinal <- storage.read(localAnchor.ordinal)
        selectedByHash <- storage.read(selectedHash)
      } yield
        expect.all(
          localHash != selectedHash,
          localByHash.isEmpty,
          localByOrdinal.isEmpty,
          selectedByHash.contains(selectedAnchor)
        )
    }
  }

  test("replaceForRecovery - retry completes cleanup after a crash between hash and ordinal replacement") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res

    File.temporaryDirectory() { tmpDir =>
      for {
        storage <- mkLocalFileSystemStorage(tmpDir)
        snapshots <- mkSnapshots
        (_, original) = snapshots
        replacementKey <- KeyPairGenerator.makeKeyPair[IO]
        replacement <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          original.value.copy(version = semver.SnapshotVersion("1.0.0")),
          replacementKey
        )
        originalHash <- original.value.hash
        replacementHash <- replacement.value.hash
        replacementBytes <- JsonSerializer[IO].serialize(replacement)
        _ <- storage.write(original)
        replacementHashFile = mkHashFile(tmpDir, replacementHash)
        _ <- IO.blocking {
          replacementHashFile.parent.createDirectories()
          replacementHashFile.writeByteArray(replacementBytes)
        }
        _ <- storage.delete(originalHash)
        // At this simulated crash cut the old ordinal still names the old
        // bytes, the new hash is durable, and the old hash has been removed.
        beforeRetry <- storage.read(original.ordinal)
        _ <- storage.replaceForRecovery(replacement)
        afterRetry <- storage.read(replacement.ordinal)
        oldAfterRetry <- storage.read(originalHash)
      } yield
        expect.all(
          beforeRetry.exists(_.value == original.value),
          afterRetry.exists(_.value == replacement.value),
          oldAfterRetry.isEmpty
        )
    }
  }

  test("ensureOrdinalLink - accepts an exact existing hash and ordinal pair") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res

    File.temporaryDirectory() { tmpDir =>
      mkLocalFileSystemStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (_, snapshot) =>
            for {
              hashed <- snapshot.toHashed
              _ <- storage.write(snapshot)
              status <- storage.ensureOrdinalLink(hashed.hash, hashed.ordinal)
            } yield expect.same(OrdinalLinkStatus.Linked, status)
        }
      }
    }
  }

  test("ensureOrdinalLink - atomically repairs a valid hash file whose ordinal hardlink is missing") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res

    File.temporaryDirectory() { tmpDir =>
      mkLocalFileSystemStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (_, snapshot) =>
            for {
              hashed <- snapshot.toHashed
              _ <- storage.write(snapshot)
              ordinalFile = mkOrdinalFile(tmpDir, snapshot)
              _ <- IO.blocking(ordinalFile.delete())
              status <- storage.ensureOrdinalLink(hashed.hash, hashed.ordinal)
              hashFile <- mkHashFile(tmpDir, snapshot)
            } yield
              expect.all(
                status == OrdinalLinkStatus.Repaired,
                ordinalFile.exists,
                ordinalFile.isSameFileAs(hashFile)
              )
        }
      }
    }
  }

  test("ensureOrdinalLink - rejects an ordinal-only snapshot whose content-addressed index is missing") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res

    File.temporaryDirectory() { tmpDir =>
      mkLocalFileSystemStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (_, snapshot) =>
            for {
              hashed <- snapshot.toHashed
              _ <- storage.write(snapshot)
              hashFile <- mkHashFile(tmpDir, snapshot)
              _ <- IO.blocking(hashFile.delete())
              status <- storage.ensureOrdinalLink(hashed.hash, hashed.ordinal)
            } yield
              expect.all(
                status == OrdinalLinkStatus.HashIndexMissing,
                mkOrdinalFile(tmpDir, snapshot).exists
              )
        }
      }
    }
  }

  test("ensureOrdinalLink - never overwrites an occupied ordinal from another branch") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res

    val differentHash = io.constellationnetwork.security.hash.Hash(List.fill(64)('a').mkString)

    File.temporaryDirectory() { tmpDir =>
      mkLocalFileSystemStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (_, snapshot) =>
            for {
              hashed <- snapshot.toHashed
              _ <- storage.write(snapshot)
              status <- storage.ensureOrdinalLink(differentHash, hashed.ordinal)
              stillStored <- storage.read(hashed.ordinal)
            } yield
              expect.all(
                status == OrdinalLinkStatus.OrdinalOccupied(hashed.ordinal, hashed.hash),
                stillStored.contains(snapshot)
              )
        }
      }
    }
  }

  test("ensureOrdinalLink - rejects bytes stored under the wrong hash path") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res

    val forgedPathHash = io.constellationnetwork.security.hash.Hash(List.fill(64)('b').mkString)

    File.temporaryDirectory() { tmpDir =>
      mkLocalFileSystemStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (_, snapshot) =>
            for {
              hashed <- snapshot.toHashed
              _ <- storage.write(snapshot)
              realHashFile <- mkHashFile(tmpDir, snapshot)
              forgedHashFile = mkHashFile(tmpDir, forgedPathHash)
              _ <- IO.blocking {
                forgedHashFile.parent.createDirectories()
                forgedHashFile.writeByteArray(realHashFile.loadBytes)
                mkOrdinalFile(tmpDir, snapshot).delete()
              }
              status <- storage.ensureOrdinalLink(forgedPathHash, hashed.ordinal)
            } yield expect.same(OrdinalLinkStatus.HashContentMismatch(hashed.ordinal, hashed.hash), status)
        }
      }
    }
  }

  test("cleanupAboveOrdinal scans sparse chunk directories and removes future ordinal/hash indexes across restart") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res
    implicit val selector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(h)

    File.temporaryDirectory() { tmpDir =>
      for {
        storage <- mkLocalFileSystemStorage(tmpDir)
        snapshots <- mkSnapshots
        (_, base) = snapshots
        key <- KeyPairGenerator.makeKeyPair[IO]
        future <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          base.value.copy(ordinal = SnapshotOrdinal(40001L)),
          key
        )
        futureHash <- future.value.hash
        _ <- storage.write(future)
        // No `ordinal/0` anchor chunk exists; the old contiguous walker stopped
        // there and never visited this later chunk.
        _ <- storage.cleanupAboveOrdinal(
          SnapshotOrdinal(1L),
          (hash, ordinal) => storage.delete(hash) >> storage.delete(ordinal)
        )
        warmOrdinal <- storage.read(future.ordinal)
        warmHash <- storage.read(futureHash)
        restarted <- mkLocalFileSystemStorage(tmpDir)
        coldOrdinal <- restarted.read(future.ordinal)
        coldHash <- restarted.read(futureHash)
      } yield expect.all(warmOrdinal.isEmpty, warmHash.isEmpty, coldOrdinal.isEmpty, coldHash.isEmpty)
    }
  }

  test("cleanupAboveOrdinal removes a future orphan hash whose ordinal index is missing") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res
    implicit val selector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(h)

    File.temporaryDirectory() { tmpDir =>
      for {
        storage <- mkLocalFileSystemStorage(tmpDir)
        snapshots <- mkSnapshots
        (_, base) = snapshots
        key <- KeyPairGenerator.makeKeyPair[IO]
        future <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          base.value.copy(ordinal = SnapshotOrdinal(40001L)),
          key
        )
        futureHash <- future.value.hash
        _ <- storage.write(future)
        _ <- storage.delete(future.ordinal)
        before <- storage.read(futureHash)
        _ <- storage.cleanupAboveOrdinal(
          SnapshotOrdinal(1L),
          (hash, ordinal) => storage.delete(hash) >> storage.delete(ordinal)
        )
        after <- storage.read(futureHash)
      } yield expect.all(before.nonEmpty, after.isEmpty)
    }
  }

  test("cleanupAboveOrdinal skips linked canonical bodies and decodes only orphan candidates") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res
    implicit val selector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(h)

    File.temporaryDirectory() { tmpDir =>
      for {
        storage <- mkLocalFileSystemStorage(tmpDir)
        _ <- (1L to 128L).toList.traverse_ { index =>
          IO.blocking {
            val hash = io.constellationnetwork.security.hash.Hash(f"$index%064x")
            val hashFile = mkHashFile(tmpDir, hash)
            val ordinalFile = tmpDir / "ordinal" / ordinalPathGenerator.get(index.toString)
            hashFile.parent.createDirectories()
            ordinalFile.parent.createDirectories()
            hashFile.writeByteArray(Array[Byte](1, 2, 3))
            hashFile.linkTo(ordinalFile)
          }
        }
        snapshots <- mkSnapshots
        (_, base) = snapshots
        key <- KeyPairGenerator.makeKeyPair[IO]
        orphan <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          base.value.copy(ordinal = SnapshotOrdinal(1001L)),
          key
        )
        orphanHash <- orphan.value.hash
        _ <- storage.write(orphan)
        _ <- storage.delete(orphan.ordinal)
        _ <- storage.cleanupAboveOrdinal(
          SnapshotOrdinal(1000L),
          (hash, ordinal) => storage.delete(hash) >> storage.delete(ordinal)
        )
        orphanAfter <- storage.read(orphanHash)
        linkedHash = io.constellationnetwork.security.hash.Hash(f"${1L}%064x")
        linkedHashFile = mkHashFile(tmpDir, linkedHash)
        linkedOrdinalFile = tmpDir / "ordinal" / ordinalPathGenerator.get("1")
      } yield
        expect.all(
          orphanAfter.isEmpty,
          linkedHashFile.exists,
          linkedOrdinalFile.exists,
          linkedHashFile.isSameFileAs(linkedOrdinalFile)
        )
    }
  }

  test("cleanupAboveOrdinal quarantines an unreadable orphan instead of blocking recovery") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res
    implicit val selector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(h)

    File.temporaryDirectory() { tmpDir =>
      val corruptHash = io.constellationnetwork.security.hash.Hash(List.fill(64)('c').mkString)
      val corruptHashFile = mkHashFile(tmpDir, corruptHash)
      val quarantinedFile = tmpDir / ".recovery-quarantine" / "hash" / corruptHash.value

      for {
        storage <- mkLocalFileSystemStorage(tmpDir)
        _ <- IO.blocking {
          corruptHashFile.parent.createDirectories()
          corruptHashFile.writeByteArray(Array[Byte](1, 2, 3))
        }
        result <- storage
          .cleanupAboveOrdinal(
            SnapshotOrdinal(1L),
            (hash, ordinal) => storage.delete(hash) >> storage.delete(ordinal)
          )
          .attempt
      } yield expect.all(clue(result).isRight, corruptHashFile.notExists, quarantinedFile.exists)
    }
  }

  test("cleanupAboveOrdinal unlinks an unreadable future ordinal and quarantines its content inode") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res
    implicit val selector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(h)

    File.temporaryDirectory() { tmpDir =>
      val corruptHash = io.constellationnetwork.security.hash.Hash(List.fill(64)('d').mkString)
      val corruptHashFile = mkHashFile(tmpDir, corruptHash)
      val corruptOrdinalFile = tmpDir / "ordinal" / ordinalPathGenerator.get("2")
      val quarantinedFile = tmpDir / ".recovery-quarantine" / "hash" / corruptHash.value

      for {
        storage <- mkLocalFileSystemStorage(tmpDir)
        _ <- IO.blocking {
          corruptHashFile.parent.createDirectories()
          corruptOrdinalFile.parent.createDirectories()
          corruptHashFile.writeByteArray(Array[Byte](1, 2, 3))
          corruptHashFile.linkTo(corruptOrdinalFile)
        }
        result <- storage
          .cleanupAboveOrdinal(
            SnapshotOrdinal(1L),
            (hash, ordinal) => storage.delete(hash) >> storage.delete(ordinal)
          )
          .attempt
      } yield
        expect.all(
          clue(result).isRight,
          corruptHashFile.notExists,
          corruptOrdinalFile.notExists,
          quarantinedFile.exists
        )
    }
  }

  test("cleanupAboveOrdinal propagates a transient hash read error without quarantining valid bytes") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res
    implicit val selector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(h)

    File.temporaryDirectory() { tmpDir =>
      for {
        ordinaryStorage <- mkLocalFileSystemStorage(tmpDir)
        snapshots <- mkSnapshots
        (_, snapshot) = snapshots
        snapshotHash <- snapshot.value.hash
        _ <- ordinaryStorage.write(snapshot)
        _ <- ordinaryStorage.delete(snapshot.ordinal)
        failedFileName = "hash/" + hashPathGenerator.get(snapshotHash.value)
        failingStorage <- mkReadFailingStorage(tmpDir, failedFileName)
        result <- failingStorage
          .cleanupAboveOrdinal(
            SnapshotOrdinal.MinValue,
            (hash, ordinal) => failingStorage.delete(hash) >> failingStorage.delete(ordinal)
          )
          .attempt
        hashFile <- mkHashFile(tmpDir, snapshot)
        quarantinedFile = tmpDir / ".recovery-quarantine" / "hash" / snapshotHash.value
      } yield expect.all(result.isLeft, hashFile.exists, quarantinedFile.notExists)
    }
  }

  test("cleanupAboveOrdinal removes a misplaced ordinal path and its newly orphaned future hash") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res
    implicit val selector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(h)

    File.temporaryDirectory() { tmpDir =>
      for {
        storage <- mkLocalFileSystemStorage(tmpDir)
        snapshots <- mkSnapshots
        (_, base) = snapshots
        key <- KeyPairGenerator.makeKeyPair[IO]
        future <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          base.value.copy(ordinal = SnapshotOrdinal(40001L)),
          key
        )
        futureHash <- future.value.hash
        _ <- storage.write(future)
        canonicalOrdinalFile = mkOrdinalFile(tmpDir, future)
        misplacedOrdinalFile = tmpDir / "ordinal" / future.ordinal.value.value.toString
        _ <- IO.blocking(canonicalOrdinalFile.moveTo(misplacedOrdinalFile))
        _ <- storage.cleanupAboveOrdinal(
          SnapshotOrdinal(1L),
          (hash, ordinal) => storage.delete(hash) >> storage.delete(ordinal)
        )
        hashAfter <- storage.read(futureHash)
      } yield expect.all(misplacedOrdinalFile.notExists, canonicalOrdinalFile.notExists, hashAfter.isEmpty)
    }
  }

  test("cleanupAboveOrdinal never moves retained history named by a mismatched future ordinal body") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res
    implicit val selector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(h)

    File.temporaryDirectory() { tmpDir =>
      for {
        storage <- mkLocalFileSystemStorage(tmpDir)
        snapshots <- mkSnapshots
        (_, retained) = snapshots
        retainedHash <- retained.value.hash
        _ <- storage.write(retained)
        retainedHashFile <- mkHashFile(tmpDir, retained)
        mismatchedFutureOrdinalFile = tmpDir / "ordinal" / ordinalPathGenerator.get("2")
        _ <- IO.blocking {
          mismatchedFutureOrdinalFile.parent.createDirectories()
          retainedHashFile.linkTo(mismatchedFutureOrdinalFile)
        }
        _ <- storage.cleanupAboveOrdinal(
          retained.ordinal,
          (hash, ordinal) => storage.delete(hash) >> storage.delete(ordinal)
        )
        byOrdinal <- storage.read(retained.ordinal)
        byHash <- storage.read(retainedHash)
      } yield
        expect.all(
          mismatchedFutureOrdinalFile.notExists,
          byOrdinal.contains(retained),
          byHash.contains(retained)
        )
    }
  }

  test("canonical cleanup structurally preserves the selected nlink-one anchor hash without decoding") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res
    implicit val selector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(h)

    File.temporaryDirectory() { tmpDir =>
      val selectedHash = io.constellationnetwork.security.hash.Hash(List.fill(64)('e').mkString)
      val selectedHashFile = mkHashFile(tmpDir, selectedHash)

      for {
        storage <- mkLocalFileSystemStorage(tmpDir)
        _ <- IO.blocking {
          selectedHashFile.parent.createDirectories()
          selectedHashFile.writeByteArray(Array[Byte](1, 2, 3))
        }
        result <- storage
          .cleanupCanonicalSuffix(
            SnapshotOrdinal(1L),
            selectedHash,
            (hash, ordinal) => storage.delete(hash) >> storage.delete(ordinal)
          )
          .attempt
      } yield expect.all(result.isRight, selectedHashFile.exists)
    }
  }

  test("cleanupAboveOrdinal fails loud on an unexpected hidden index directory") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res
    implicit val selector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(h)

    File.temporaryDirectory() { tmpDir =>
      val unexpectedDirectory = tmpDir / "hash" / "aaa" / "bbb" / "hidden"

      for {
        storage <- mkLocalFileSystemStorage(tmpDir)
        _ <- IO.blocking(unexpectedDirectory.createDirectories())
        result <- storage
          .cleanupAboveOrdinal(
            SnapshotOrdinal(1L),
            (hash, ordinal) => storage.delete(hash) >> storage.delete(ordinal)
          )
          .attempt
      } yield expect.all(result.isLeft, unexpectedDirectory.exists)
    }
  }

  test("cleanupAboveOrdinal fails loud between hash/ordinal deletes and converges on retry") { res =>
    implicit val (_, kryo, j, h, sp, gsps) = res
    implicit val selector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(h)

    File.temporaryDirectory() { tmpDir =>
      for {
        storage <- mkLocalFileSystemStorage(tmpDir)
        snapshots <- mkSnapshots
        (_, base) = snapshots
        key <- KeyPairGenerator.makeKeyPair[IO]
        future <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
          base.value.copy(ordinal = SnapshotOrdinal(2L)),
          key
        )
        futureHash <- future.value.hash
        _ <- storage.write(future)
        first <- storage
          .cleanupAboveOrdinal(
            SnapshotOrdinal(1L),
            (hash, _) => storage.delete(hash) >> IO.raiseError(new RuntimeException("simulated crash cut"))
          )
          .attempt
        // The ordinal hardlink still owns readable bytes after hash-first
        // cleanup, so an idempotent retry can rediscover the same future value.
        afterCut <- storage.read(future.ordinal)
        _ <- storage.cleanupAboveOrdinal(
          SnapshotOrdinal(1L),
          (hash, ordinal) => storage.delete(hash) >> storage.delete(ordinal)
        )
        finalOrdinal <- storage.read(future.ordinal)
        finalHash <- storage.read(futureHash)
      } yield expect.all(first.isLeft, afterCut.nonEmpty, finalOrdinal.isEmpty, finalHash.isEmpty)
    }
  }

}
