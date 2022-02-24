package org.tessellation.infrastructure.snapshot

import cats.effect.{IO, Resource}
import cats.syntax.option._

import org.tessellation.dag.dagSharedKryoRegistrar
import org.tessellation.dag.snapshot.{GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.sdk.sdkKryoRegistrar

import better.files._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.Path
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotStorageSuite extends MutableIOSuite with Checkers {

  type Res = KryoSerializer[IO]

  override def sharedResource: Resource[IO, GlobalSnapshotStorageSuite.Res] =
    KryoSerializer.forAsync[IO](dagSharedKryoRegistrar ++ sdkKryoRegistrar)

  def mkStorage(tmpDir: File)(implicit K: KryoSerializer[IO]) =
    GlobalSnapshotLocalFileSystemStorage.make[IO](Path(tmpDir.pathAsString)).flatMap {
      GlobalSnapshotStorage.make[IO](_, genesis, NonNegLong(5))
    }

  def snapshot(implicit K: KryoSerializer[IO]): GlobalSnapshot =
    GlobalSnapshot(
      genesis.ordinal.next,
      Height.MinValue,
      SubHeight.MinValue,
      genesis.hash.toOption.get,
      Set.empty,
      Map.empty,
      genesisNextFacilitators
    )

  test("head - returns genesis for empty storage") { implicit kryo =>
    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        storage.head.map {
          expect.same(_, genesis)
        }
      }
    }
  }

  test("head - returns latest snapshot if not empty") { implicit kryo =>
    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        storage.prepend(snapshot) >>
          storage.head.map {
            expect.same(_, snapshot)
          }
      }
    }
  }

  test("prepend - should return true if next snapshot creates a chain") { implicit kryo =>
    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        storage.prepend(snapshot).map { expect.same(_, true) }
      }
    }
  }

  test("prepend - should return false if snapshot does not create a chain") { implicit kryo =>
    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        storage.prepend(genesis).map { expect.same(_, false) }
      }
    }
  }

  test("get - should return genesis snapshot by ordinal") { implicit kryo =>
    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        storage.get(SnapshotOrdinal.MinValue).map { expect.same(_, genesis.some) }
      }
    }
  }

  test("get - should return snapshot by ordinal") { implicit kryo =>
    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        storage.prepend(snapshot) >>
          storage.get(snapshot.ordinal).map { expect.same(_, snapshot.some) }
      }
    }
  }
}
