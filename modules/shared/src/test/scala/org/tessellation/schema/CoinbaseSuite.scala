package org.tessellation.schema

import cats.effect.{IO, Resource}

import org.tessellation.ext.crypto.RefinedHashableF
import org.tessellation.kryo.KryoSerializer
import org.tessellation.shared.sharedKryoRegistrar

import suite.ResourceSuite
import weaver.scalacheck.Checkers

object CoinbaseSuite extends ResourceSuite with Checkers {

  override type Res = KryoSerializer[IO]

  override def sharedResource: Resource[IO, KryoSerializer[IO]] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar)

  test("coinbase hash should be constant and known") { implicit kp =>
    Coinbase.value.hashF.map(
      expect.same(
        Coinbase.hash,
        _
      )
    )
  }
}
