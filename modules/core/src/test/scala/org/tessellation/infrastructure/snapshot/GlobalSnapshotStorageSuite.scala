package org.tessellation.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.effect.std.Supervisor
import cats.effect.{IO, Resource}
import cats.syntax.option._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.dag.dagSharedKryoRegistrar
import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.dag.snapshot.epoch.EpochProgress
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.kryo._
import org.tessellation.keytool.KeyPairGenerator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ext.crypto._
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.schema.security.hex.Hex
import org.tessellation.schema.security.signature.Signed
import org.tessellation.schema.{address, balance}
import org.tessellation.sdk.sdkKryoRegistrar

import better.files._
import eu.timepit.refined.auto._
import fs2.io.file.Path
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotStorageSuite extends MutableIOSuite with Checkers {

  type Res = (Supervisor[IO], KryoSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, GlobalSnapshotStorageSuite.Res] =
    Supervisor[IO].flatMap { supervisor =>
      KryoSerializer.forAsync[IO](dagSharedKryoRegistrar.union(sdkKryoRegistrar)).flatMap { ks =>
        SecurityProvider.forAsync[IO].map((supervisor, ks, _))
      }
    }

  def mkStorage(tmpDir: File)(implicit K: KryoSerializer[IO], S: Supervisor[IO]) =
    GlobalSnapshotLocalFileSystemStorage.make[IO](Path(tmpDir.pathAsString)).flatMap {
      GlobalSnapshotStorage.make[IO](_, 5L, None)
    }

  def mkSnapshots(
    implicit K: KryoSerializer[IO],
    S: SecurityProvider[IO]
  ): IO[(Signed[GlobalSnapshot], Signed[GlobalSnapshot])] =
    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      Signed.forAsyncKryo[IO, GlobalSnapshot](GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue), keyPair).flatMap { genesis =>
        def snapshot =
          GlobalSnapshot(
            genesis.value.ordinal.next,
            Height.MinValue,
            SubHeight.MinValue,
            genesis.value.hash.toOption.get,
            SortedSet.empty,
            SortedMap.empty,
            SortedSet.empty,
            genesis.value.epochProgress,
            NonEmptyList.of(PeerId(Hex("peer1"))),
            genesis.info,
            genesis.tips
          )

        Signed.forAsyncKryo[IO, GlobalSnapshot](snapshot, keyPair).map((genesis, _))
      }
    }

  test("head - returns none for empty storage") { res =>
    implicit val (s, kryo, _) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        storage.head.map {
          expect.same(_, none)
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
            storage.prepend(genesis) >>
              storage.prepend(snapshot) >>
              storage.head.map {
                expect.same(_, snapshot.some)
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
            storage.prepend(genesis) >>
              storage.prepend(snapshot).map(expect.same(_, true))
        }
      }
    }
  }

  test("prepend - should allow to start from any arbitrary snapshot") { res =>
    implicit val (s, kryo, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (_, snapshot) =>
            storage.prepend(snapshot).map(expect.same(_, true))
        }
      }
    }
  }

  test("get - should return snapshot by ordinal") { res =>
    implicit val (s, kryo, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, _) =>
            storage.prepend(genesis) >>
              storage.get(genesis.ordinal).map(expect.same(_, genesis.some))
        }
      }
    }
  }

  test("get - should return snapshot by hash") { res =>
    implicit val (s, kryo, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, _) =>
            storage.prepend(genesis) >>
              genesis.value.hashF.flatMap { hash =>
                storage.get(hash).map(expect.same(_, genesis.some))
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
          case (genesis, _) =>
            storage.prepend(genesis) >>
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
          case (genesis, _) =>
            storage.prepend(genesis) >>
              storage.getLatestBalancesStream.take(1).compile.toList >>
              storage.getLatestBalancesStream.take(1).compile.toList.map {
                expect.same(_, List(Map.empty[address.Address, balance.Balance]))
              }
        }
      }
    }
  }
}
