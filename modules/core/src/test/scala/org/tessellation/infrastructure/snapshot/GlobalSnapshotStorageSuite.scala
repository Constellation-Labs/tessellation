package org.tessellation.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.option._

import org.tessellation.dag.dagSharedKryoRegistrar
import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.ext.kryo._
import org.tessellation.keytool.KeyPairGenerator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed

import better.files._
import eu.timepit.refined.auto._
import fs2.io.file.Path
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotStorageSuite extends MutableIOSuite with Checkers {

  type Res = (KryoSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, GlobalSnapshotStorageSuite.Res] =
    KryoSerializer.forAsync[IO](dagSharedKryoRegistrar.union(sdkKryoRegistrar)).flatMap { ks =>
      SecurityProvider.forAsync[IO].map((ks, _))
    }

  def mkStorage(tmpDir: File)(implicit K: KryoSerializer[IO]) =
    GlobalSnapshotLocalFileSystemStorage.make[IO](Path(tmpDir.pathAsString)).flatMap {
      GlobalSnapshotStorage.make[IO](_, 5L)
    }

  def mkSnapshots(
    implicit K: KryoSerializer[IO],
    S: SecurityProvider[IO]
  ): IO[(Signed[GlobalSnapshot], Signed[GlobalSnapshot])] =
    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      Signed.forAsyncKryo[IO, GlobalSnapshot](GlobalSnapshot.mkGenesis(Map.empty), keyPair).flatMap { genesis =>
        def snapshot =
          GlobalSnapshot(
            genesis.value.ordinal.next,
            Height.MinValue,
            SubHeight.MinValue,
            genesis.value.hash.toOption.get,
            Set.empty,
            genesis.value.balances,
            Map.empty,
            NonEmptyList.of(PeerId(Hex("peer1"))),
            genesis.info
          )

        Signed.forAsyncKryo[IO, GlobalSnapshot](snapshot, keyPair).map((genesis, _))
      }
    }

  test("head - returns none for empty storage") { res =>
    implicit val (kryo, _) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        storage.head.map {
          expect.same(_, none)
        }
      }
    }
  }

  test("head - returns latest snapshot if not empty") { res =>
    implicit val (kryo, sp) = res

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
    implicit val (kryo, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(genesis) >>
              storage.prepend(snapshot).map { expect.same(_, true) }
        }
      }
    }
  }

  test("prepend - should allow to start from any arbitrary snapshot") { res =>
    implicit val (kryo, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (_, snapshot) =>
            storage.prepend(snapshot).map { expect.same(_, true) }
        }
      }
    }
  }

  test("get - should return snapshot by ordinal") { res =>
    implicit val (kryo, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, _) =>
            storage.prepend(genesis) >>
              storage.get(genesis.ordinal).map { expect.same(_, genesis.some) }
        }
      }
    }
  }

  test("get - should return snapshot by hash") { res =>
    implicit val (kryo, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, _) =>
            storage.prepend(genesis) >>
              genesis.value.hashF.flatMap { hash =>
                storage.get(hash).map { expect.same(_, genesis.some) }
              }
        }
      }
    }
  }
}
