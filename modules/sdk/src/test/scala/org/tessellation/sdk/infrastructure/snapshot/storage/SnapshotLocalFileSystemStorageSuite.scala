package org.tessellation.sdk.infrastructure.snapshot.storage

import cats.effect.std.Supervisor
import cats.effect.{IO, Resource}

import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.signature.Signed
import org.tessellation.security.{KeyPairGenerator, SecurityProvider}
import org.tessellation.shared.sharedKryoRegistrar

import better.files._
import eu.timepit.refined.auto._
import fs2.io.file.Path
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

import SnapshotLocalFileSystemStorage.UnableToPersisSnapshot

object SnapshotLocalFileSystemStorageSuite extends MutableIOSuite with Checkers {

  type Res = (Supervisor[IO], KryoSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] =
    Supervisor[IO].flatMap { supervisor =>
      KryoSerializer.forAsync[IO](sharedKryoRegistrar.union(sdkKryoRegistrar)).flatMap { ks =>
        SecurityProvider.forAsync[IO].map((supervisor, ks, _))
      }
    }

  private def mkLocalFileSystemStorage(tmpDir: File)(implicit K: KryoSerializer[IO]) =
    SnapshotLocalFileSystemStorage.make[IO, GlobalIncrementalSnapshot](Path(tmpDir.pathAsString))

  private def mkSnapshots(
    implicit K: KryoSerializer[IO],
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
    implicit K: KryoSerializer[IO]
  ): IO[UnableToPersisSnapshot] =
    snapshot.toHashed.map(hashed =>
      UnableToPersisSnapshot(
        hashed.ordinal.value.toString,
        hashed.hash.value,
        hashFileExists = hashFileExists
      )
    )

  private def mkHashFile(tmpDir: File, snapshot: Signed[GlobalIncrementalSnapshot])(
    implicit K: KryoSerializer[IO]
  ): IO[File] =
    snapshot.toHashed.map(hashed => tmpDir / hashed.hash.value)

  private def mkOrdinalFile(tmpDir: File, snapshot: Signed[GlobalIncrementalSnapshot]): File =
    tmpDir / snapshot.ordinal.value.value.toString

  test("write - fail if ordinal file and hash file already exist") { res =>
    implicit val (_, kryo, sp) = res

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
    implicit val (_, kryo, sp) = res

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
    implicit val (_, kryo, sp) = res

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
    implicit val (_, kryo, sp) = res

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
