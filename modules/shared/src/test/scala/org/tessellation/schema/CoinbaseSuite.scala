package org.tessellation.schema

import cats.effect.{IO, Resource}

import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security._
import org.tessellation.shared.sharedKryoRegistrar

import suite.ResourceSuite
import weaver.scalacheck.Checkers

object CoinbaseSuite extends ResourceSuite with Checkers {

  override type Res = Hasher[IO]

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { implicit res =>
      JsonSerializer.forSync[IO].asResource.map { implicit json =>
        Hasher.forSync[IO](new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash })
      }
    }

  test("coinbase hash should be constant and known") { implicit res =>
    res
      .compare(Coinbase.value, Coinbase.hash)
      .map(expect(_))
  }
}
