package org.tessellation.infrastructure.snapshot

import cats.effect.{IO, Resource}

import org.tessellation.ext.crypto._
import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.sdk.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{KeyPairGenerator, SecurityProvider}
import org.tessellation.shared.sharedKryoRegistrar
import org.tessellation.storage.LocalFileSystemStorage

import better.files._
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Interval
import fs2.io.file.Path
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotLoaderSuite extends MutableIOSuite with Checkers {

  type Res = (KryoSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, GlobalSnapshotLoaderSuite.Res] =
    KryoSerializer
      .forAsync[IO](
        sharedKryoRegistrar
          .union(sdkKryoRegistrar)
          .union(Map[Class[_], KryoRegistrationId[Interval.Closed[5000, 5001]]](classOf[TestObject] -> 5000))
      )
      .flatMap { ks =>
        SecurityProvider.forAsync[IO].map((ks, _))
      }

  def mkLoader(tmpDir: File)(implicit K: KryoSerializer[IO]) = for {
    incrementalFileStorage <- SnapshotLocalFileSystemStorage.make[IO, GlobalIncrementalSnapshot](Path((tmpDir / "incremental").toString()))
    globalFileStorage <- SnapshotLocalFileSystemStorage.make[IO, GlobalSnapshot](Path((tmpDir / "global").toString()))
    loader = GlobalSnapshotLoader.make[IO](incrementalFileStorage, globalFileStorage)
  } yield (incrementalFileStorage, globalFileStorage, loader)

  def mkSnapshots(
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

  def mkTestObject(
    implicit K: KryoSerializer[IO],
    S: SecurityProvider[IO]
  ): IO[Signed[TestObject]] =
    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      Signed.forAsyncKryo[IO, TestObject](TestObject("test"), keyPair)
    }

  test("throw an exception when there is no snapshot's file") { res =>
    implicit val (kryo, _) = res
    File.temporaryDirectory() { tmpDir =>
      mkLoader(tmpDir).flatMap {
        case (_, _, loader) =>
          loader.readGlobalSnapshot(Hash.empty).attempt.map { result =>
            expect.eql(true, result.isLeft)
          }
      }
    }
  }

  test("return incremental global snapshot when exists") { res =>
    implicit val (kryo, sp) = res
    File.temporaryDirectory() { tmpDir =>
      mkLoader(tmpDir).flatMap {
        case (incrementalStorage, _, loader) =>
          mkSnapshots.flatMap {
            case (_, incremental) =>
              val incrementalHash = incremental.value.hash.toOption.get
              incrementalStorage.write(incrementalHash.value, incremental) >>
                loader.readGlobalSnapshot(incrementalHash).map { result =>
                  expect.same(Right(incremental), result)
                }
          }
      }
    }
  }

  test("return global snapshot when exists") { res =>
    implicit val (kryo, sp) = res
    File.temporaryDirectory() { tmpDir =>
      mkLoader(tmpDir).flatMap {
        case (_, globalFileStorage, loader) =>
          mkSnapshots.flatMap {
            case (genesis, _) =>
              val genesisHash = genesis.value.hash.toOption.get
              globalFileStorage.write(genesisHash.value, genesis) >>
                loader.readGlobalSnapshot(genesisHash).map { result =>
                  expect.same(Left(genesis), result)
                }
          }
      }
    }
  }

  test("throw an exception when file cannot be deserialized neither as GlobalIncrementalSnapshot nor GlobalSnapshot") { res =>
    implicit val (kryo, sp) = res
    File.temporaryDirectory() { tmpDir =>
      mkLoader(tmpDir).flatMap {
        case (_, _, loader) =>
          mkTestObject.flatMap { testObject =>
            val objectFileSystem = new LocalFileSystemStorage[IO, Object](Path(tmpDir.pathAsString)) {}
            val objectHash = Hash.empty
            objectFileSystem.write(objectHash.value, testObject) >>
              loader.readGlobalSnapshot(objectHash).attempt.map { result =>
                expect.eql(true, result.isLeft)
              }
          }
      }
    }
  }

  case class TestObject(val value: String)
}
