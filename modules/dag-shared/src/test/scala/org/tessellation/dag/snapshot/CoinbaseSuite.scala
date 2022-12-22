package org.tessellation.dag.snapshot

import cats.effect.{IO, Resource}

import org.tessellation.dag.dagSharedKryoRegistrar
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ext.crypto.RefinedHashableF

import suite.ResourceSuite
import weaver.scalacheck.Checkers

object CoinbaseSuite extends ResourceSuite with Checkers {

  override type Res = KryoSerializer[IO]

  override def sharedResource: Resource[IO, KryoSerializer[IO]] =
    KryoSerializer.forAsync[IO](dagSharedKryoRegistrar)

  test("coinbase hash should be constant and known") { implicit kp =>
    Coinbase.value.hashF.map(
      expect.same(
        Coinbase.hash,
        _
      )
    )
  }
}
