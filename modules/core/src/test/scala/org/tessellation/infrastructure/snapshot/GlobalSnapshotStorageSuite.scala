package org.tessellation.infrastructure.snapshot

import cats.effect.{IO, Resource}
import cats.syntax.either._
import cats.syntax.option._

import org.tessellation.dag.dagSharedKryoRegistrar
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.infrastructure.snapshot.GlobalSnapshotStorage.InvalidGlobalSnapshotChain
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.kryo.sdkKryoRegistrar

import monocle.syntax.all._
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotStorageSuite extends MutableIOSuite with Checkers {

  type Res = KryoSerializer[IO]

  override def sharedResource: Resource[IO, GlobalSnapshotStorageSuite.Res] =
    KryoSerializer.forAsync[IO](dagSharedKryoRegistrar ++ sdkKryoRegistrar)

  test("should accept valid snapshot") { implicit kryo =>
    for {
      storage <- GlobalSnapshotStorage.make(genesis)
      genesisHash <- genesis.hash.liftTo[IO]
      nextSnapshot = genesis
        .focus(_.ordinal)
        .modify(_.next)
        .focus(_.lastSnapshotHash)
        .replace(genesisHash)
      _ <- storage.save(nextSnapshot)
    } yield success
  }

  test("should not accept snapshot with invalid `lastSnapshotHash`") { implicit kryo =>
    for {
      storage <- GlobalSnapshotStorage.make(genesis)
      nextSnapshot = genesis
        .focus(_.ordinal)
        .modify(_.next)
      maybeError <- storage
        .save(nextSnapshot)
        .map(_ => none[Throwable])
        .handleError(_.some)
    } yield verify(maybeError.exists(_.isInstanceOf[InvalidGlobalSnapshotChain]))
  }

  test("should not accept snapshot with invalid `ordinal`") { implicit kryo =>
    for {
      storage <- GlobalSnapshotStorage.make(genesis)
      genesisHash <- genesis.hash.liftTo[IO]
      nextSnapshot = genesis
        .focus(_.lastSnapshotHash)
        .replace(genesisHash)
      maybeError <- storage
        .save(nextSnapshot)
        .map(_ => none[Throwable])
        .handleError(_.some)
    } yield verify(maybeError.exists(_.isInstanceOf[InvalidGlobalSnapshotChain]))
  }

}
