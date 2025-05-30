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

import SnapshotLocalFileSystemStorage.UnableToPersistSnapshot

object SnapshotLocalFileSystemStorageSuite extends MutableIOSuite with Checkers {

  val hashPathGenerator = PathGenerator.forHash(Depth(2), PrefixSize(3))
  val ordinalPathGenerator = PathGenerator.forOrdinal(ChunkSize(20000))

  type Res = (Supervisor[IO], KryoSerializer[IO], JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      s <- Supervisor[IO]
      implicit0(k: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar.union(nodeSharedKryoRegistrar))
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
      h = Hasher.forJson[IO]
      sp <- SecurityProvider.forAsync[IO]
    } yield (s, k, j, h, sp)

  private def mkLocalFileSystemStorage(tmpDir: File)(implicit K: KryoSerializer[IO], J: JsonSerializer[IO], H: Hasher[IO]) =
    GlobalIncrementalSnapshotLocalFileSystemStorage.make[IO](Path(tmpDir.pathAsString))

  private def mkSnapshots(
    implicit H: Hasher[IO],
    S: SecurityProvider[IO]
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

  private def mkOrdinalFile(tmpDir: File, snapshot: Signed[GlobalIncrementalSnapshot]): File =
    tmpDir / "ordinal" / ordinalPathGenerator.get(snapshot.ordinal.value.value.toString)

  test("write - fail if ordinal file and hash file already exist") { res =>
    implicit val (_, kryo, j, h, sp) = res

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
    implicit val (_, kryo, j, h, sp) = res

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
    implicit val (_, kryo, j, h, sp) = res

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
    implicit val (_, kryo, j, h, sp) = res

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

}
