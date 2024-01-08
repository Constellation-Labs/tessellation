package org.tessellation.schema

import cats.effect.{IO, Resource}

import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.json.JsonHashSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.Hasher
import org.tessellation.shared.sharedKryoRegistrar

import suite.ResourceSuite
import weaver.scalacheck.Checkers

object CoinbaseSuite extends ResourceSuite with Checkers {

  override type Res = Hasher[IO]

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { implicit res =>
      JsonHashSerializer.forSync[IO].asResource.map { implicit json =>
        Hasher.forSync[IO]
      }
    }

  test("coinbase hash should be constant and known") { implicit res =>
    res
      .hash(Coinbase.value)
      .map(
        expect.same(
          Coinbase.hash,
          _
        )
      )
  }
}
