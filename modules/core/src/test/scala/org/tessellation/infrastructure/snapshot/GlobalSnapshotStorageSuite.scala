package org.tessellation.infrastructure.snapshot

import cats.effect.{IO, Resource}
import cats.syntax.either._
import cats.syntax.option._

import scala.reflect.ClassTag

import org.tessellation.dag.dagSharedKryoRegistrar
import org.tessellation.dag.snapshot.{GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.ext.swaydb.SwaydbRef
import org.tessellation.infrastructure.snapshot.GlobalSnapshotStorage.InvalidGlobalSnapshotChain
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.sdkKryoRegistrar

import io.chrisdavenport.mapref.MapRef
import monocle.syntax.all._
import swaydb.data.slice.Slice
import swaydb.serializers.Serializer
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotStorageSuite extends MutableIOSuite with Checkers {

  type Res = (KryoSerializer[IO], MapRef[IO, SnapshotOrdinal, Option[GlobalSnapshot]])

  def kryoSerializer[G[_]: KryoSerializer, A <: AnyRef: ClassTag] = new Serializer[A] {
    override def write(cls: A): Slice[Byte] =
      KryoSerializer[G].serialize(cls).fold(e => throw e, Slice.apply)
    override def read(slice: Slice[Byte]): A =
      KryoSerializer[G].deserialize[A](slice.toArray).fold(e => throw e, identity)
  }

  override def sharedResource: Resource[IO, GlobalSnapshotStorageSuite.Res] =
    KryoSerializer.forAsync[IO](dagSharedKryoRegistrar ++ sdkKryoRegistrar).flatMap { implicit kryo =>
      implicit val snapshotOrdinalSerializer = kryoSerializer[IO, SnapshotOrdinal]
      implicit val globalSnapshotSerializer = kryoSerializer[IO, GlobalSnapshot]

      SwaydbRef[IO]
        .memory[SnapshotOrdinal, GlobalSnapshot]
        .flatMap { r =>
          Resource.liftK[IO] { r(genesis.ordinal).set(genesis.some).map(_ => r) }
        }
        .map((kryo, _))
    }

  test("should accept valid snapshot") { res =>
    implicit val (kryo, ref) = res
    val storage = GlobalSnapshotStorage.make(ref)

    genesis.hash
      .liftTo[IO]
      .map {
        genesis.focus(_.ordinal).modify(_.next).focus(_.lastSnapshotHash).replace(_)
      }
      .flatMap(storage.save)
      .map(_ => success)
  }

  test("should not accept snapshot with invalid `lastSnapshotHash`") { res =>
    implicit val (kryo, ref) = res
    val storage = GlobalSnapshotStorage.make(ref)
    val nextSnapshot = genesis
      .focus(_.ordinal)
      .modify(_.next)

    for {
      maybeError <- storage
        .save(nextSnapshot)
        .map(_ => none[Throwable])
        .handleError(_.some)
    } yield verify(maybeError.exists(_.isInstanceOf[InvalidGlobalSnapshotChain]))
  }

  test("should not accept snapshot with invalid `ordinal`") { res =>
    implicit val (kryo, ref) = res
    val storage = GlobalSnapshotStorage.make(ref)

    for {
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

  test("`getLast` should return genesis when no snapshots are stored") { res =>
    implicit val (kryo, ref) = res
    val storage = GlobalSnapshotStorage.make(ref)

    storage.getLast.map {
      expect.same(genesis, _)
    }

  }

}
