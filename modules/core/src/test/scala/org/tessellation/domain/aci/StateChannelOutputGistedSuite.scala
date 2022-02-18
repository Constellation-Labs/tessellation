package org.tessellation.domain.aci

import cats.effect.{IO, Resource}
import cats.syntax.option._

import org.tessellation.coreKryoRegistrar
import org.tessellation.ext.kryo._
import org.tessellation.kernel.{StateChannelSnapshot, kernelKryoRegistrar}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.hash.Hash

import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object StateChannelOutputGistedSuite extends MutableIOSuite with Checkers {
  type Res = KryoSerializer[IO]

  // not registered in Kryo
  case class NonSerializableSnapshot(lastSnapshotHash: Hash = Hash("0")) extends StateChannelSnapshot {}

  override def sharedResource: Resource[IO, StateChannelOutputGistedSuite.Res] =
    KryoSerializer.forAsync[IO](coreKryoRegistrar ++ kernelKryoRegistrar)

  test("raw snapshot should not be serializable") { implicit kryo =>
    val nonSerializableSnapshot = NonSerializableSnapshot()

    for {
      maybeError <- nonSerializableSnapshot.toBinaryF
        .map(_ => none[Throwable])
        .handleError(_.some)
    } yield verify(maybeError.isDefined, maybeError.fold("none")(_.getMessage))
  }

  test("snapshot gist should be serializable") { implicit kryo =>
    val nonSerializableSnapshot = NonSerializableSnapshot()

    nonSerializableSnapshot.gist.toBinaryF.map(_ => success)
  }
}
