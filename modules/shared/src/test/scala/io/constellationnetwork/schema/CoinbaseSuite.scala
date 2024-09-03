package io.constellationnetwork.schema

import cats.effect.{IO, Resource}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.security._
import io.constellationnetwork.shared.sharedKryoRegistrar

import suite.ResourceSuite
import weaver.scalacheck.Checkers

object CoinbaseSuite extends ResourceSuite with Checkers {

  override type Res = Hasher[IO]

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { implicit res =>
      JsonSerializer.forSync[IO].asResource.map { implicit json =>
        Hasher.forKryo[IO]
      }
    }

  test("coinbase hash should be constant and known") { implicit res =>
    res
      .compare(Coinbase.value, Coinbase.hash)
      .map(expect(_))
  }
}
