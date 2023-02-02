package org.tessellation.infrastructure.snapshot

import cats.effect.std.Supervisor
import cats.effect.{IO, Resource}
import cats.syntax.option._

import org.tessellation.ext.crypto._
import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.sdk.infrastructure.snapshot.storage.{SnapshotLocalFileSystemStorage, SnapshotStorage}
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.{GlobalSnapshot, address, balance}
import org.tessellation.sdk.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.{GlobalSnapshot, address, balance, _}
import org.tessellation.sdk.infrastructure.snapshot.storage.{SnapshotLocalFileSystemStorage, SnapshotStorage}
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed
import org.tessellation.security.{KeyPairGenerator, SecurityProvider}
import org.tessellation.shared.sharedKryoRegistrar

import better.files._
import eu.timepit.refined.auto._
import fs2.io.file.Path
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers
object SnapshotStorageSuite extends MutableIOSuite with Checkers {

  type Res = (Supervisor[IO], KryoSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, SnapshotStorageSuite.Res] =
    Supervisor[IO].flatMap { supervisor =>
      KryoSerializer.forAsync[IO](sharedKryoRegistrar.union(sdkKryoRegistrar)).flatMap { ks =>
        SecurityProvider.forAsync[IO].map((supervisor, ks, _))
      }
    }

  def mkStorage(tmpDir: File)(implicit K: KryoSerializer[IO], S: Supervisor[IO]) =
    SnapshotLocalFileSystemStorage.make[IO, IncrementalGlobalSnapshot](Path(tmpDir.pathAsString)).flatMap {
      SnapshotStorage.make[IO, IncrementalGlobalSnapshot, GlobalSnapshotInfo](_, 5L)
    }

  def mkSnapshots(
    implicit K: KryoSerializer[IO],
    S: SecurityProvider[IO]
  ): IO[(Signed[GlobalSnapshot], Signed[IncrementalGlobalSnapshot])] =
    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      Signed.forAsyncKryo[IO, GlobalSnapshot](GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue), keyPair).flatMap { genesis =>
        IncrementalGlobalSnapshot.fromGlobalSnapshot(genesis).flatMap { snapshot =>
          Signed.forAsyncKryo[IO, IncrementalGlobalSnapshot](snapshot, keyPair).map((genesis, _))
        }
        def snapshot =
          IncrementalGlobalSnapshot(
            genesis.value.ordinal.next,
            Height.MinValue,
            SubHeight.MinValue,
            genesis.value.hash.toOption.get,
            SortedSet.empty,
            SortedMap.empty,
            SortedSet.empty,
            genesis.value.epochProgress,
            NonEmptyList.of(PeerId(Hex("peer1"))),
            genesis.tips
          )

        Signed.forAsyncKryo[IO, IncrementalGlobalSnapshot](snapshot, keyPair).map((genesis, _))
      }
    }

  test("head - returns none for empty storage") { res =>
    implicit val (s, kryo, _) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        storage.head.map {
          expect.eql(none, _)
        }
      }
    }
  }

  test("head - returns latest snapshot if not empty") { res =>
    implicit val (s, kryo, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info) >>
              storage.headSnapshot.map {
                expect.eql(snapshot.some, _)
              }
        }
      }
    }
  }

  test("prepend - should return true if next snapshot creates a chain") { res =>
    implicit val (s, kryo, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info).map(expect.eql(true, _))
        }
      }
    }
  }

  test("prepend - should allow to start from any arbitrary snapshot") { res =>
    implicit val (s, kryo, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info).map(expect.same(true, _))
        }
      }
    }
  }

  test("get - should return snapshot by ordinal") { res =>
    implicit val (s, kryo, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info) >>
              storage.get(snapshot.ordinal).map(expect.eql(snapshot.some, _))
        }
      }
    }
  }

  test("get - should return snapshot by hash") { res =>
    implicit val (s, kryo, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info) >>
              snapshot.value.hashF.flatMap { hash =>
                storage.get(hash).map(expect.eql(snapshot.some, _))
              }
        }
      }
    }
  }

  test("getLatestBalancesStream - subscriber should get latest balances") { res =>
    implicit val (s, kryo, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info) >>
              storage.getLatestBalancesStream.take(1).compile.toList.map {
                expect.same(_, List(Map.empty[address.Address, balance.Balance]))
              }
        }
      }
    }
  }

  test("getLatestBalancesStream - second subscriber should get latest balances") { res =>
    implicit val (s, kryo, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info) >>
              storage.getLatestBalancesStream.take(1).compile.toList >>
              storage.getLatestBalancesStream.take(1).compile.toList.map {
                expect.same(_, List(Map.empty[address.Address, balance.Balance]))
              }
        }
      }
    }
  }
}
