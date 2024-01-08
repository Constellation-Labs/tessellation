package org.tessellation.node.shared.infrastructure.snapshot.storage

import cats.effect.std.Supervisor
import cats.effect.{IO, Resource}

import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.kryo._
import org.tessellation.json.JsonHashSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.nodeSharedKryoRegistrar
import org.tessellation.schema._
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hasher, KeyPairGenerator, SecurityProvider}
import org.tessellation.shared.sharedKryoRegistrar

import better.files._
import eu.timepit.refined.auto._
import fs2.io.file.Path
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

import SnapshotLocalFileSystemStorage.UnableToPersistSnapshot

object SnapshotLocalFileSystemStorageSuite extends MutableIOSuite with Checkers {

  type Res = (Supervisor[IO], KryoSerializer[IO], Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      s <- Supervisor[IO]
      implicit0(k: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar.union(nodeSharedKryoRegistrar))
      implicit0(j: JsonHashSerializer[IO]) <- JsonHashSerializer.forSync[IO].asResource
      h = Hasher.forSync[IO]
      sp <- SecurityProvider.forAsync[IO]
    } yield (s, k, h, sp)

  private def mkLocalFileSystemStorage(tmpDir: File)(implicit K: KryoSerializer[IO], H: Hasher[IO]) =
    SnapshotLocalFileSystemStorage.make[IO, GlobalIncrementalSnapshot](Path(tmpDir.pathAsString))

  private def mkSnapshots(
    implicit H: Hasher[IO],
    S: SecurityProvider[IO]
  ): IO[(Signed[GlobalSnapshot], Signed[GlobalIncrementalSnapshot])] =
    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      Signed.forAsyncKryo[IO, GlobalSnapshot](GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue), keyPair).flatMap { genesis =>
        GlobalIncrementalSnapshot.fromGlobalSnapshot(genesis).flatMap { snapshot =>
          Signed.forAsyncKryo[IO, GlobalIncrementalSnapshot](snapshot, keyPair).map((genesis, _))
        }
      }
    }

  private def mkError(snapshot: Signed[GlobalIncrementalSnapshot], hashFileExists: Boolean)(
    implicit H: Hasher[IO]
  ): IO[UnableToPersistSnapshot] =
    snapshot.toHashed.map(hashed =>
      UnableToPersistSnapshot(
        hashed.ordinal.value.toString,
        hashed.hash.value,
        hashFileExists = hashFileExists
      )
    )

  private def mkHashFile(tmpDir: File, snapshot: Signed[GlobalIncrementalSnapshot])(
    implicit H: Hasher[IO]
  ): IO[File] =
    snapshot.toHashed.map(hashed => tmpDir / hashed.hash.value)

  private def mkOrdinalFile(tmpDir: File, snapshot: Signed[GlobalIncrementalSnapshot]): File =
    tmpDir / snapshot.ordinal.value.value.toString

  test("write - fail if ordinal file and hash file already exist") { res =>
    implicit val (_, kryo, h, sp) = res

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
    implicit val (_, kryo, h, sp) = res

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
    implicit val (_, kryo, h, sp) = res

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
    implicit val (_, kryo, h, sp) = res

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
