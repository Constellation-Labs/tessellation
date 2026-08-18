package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.effect.std.Supervisor
import cats.effect.{IO, Resource}

import io.constellationnetwork.ext.cats.effect.ResourceIO
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

}
