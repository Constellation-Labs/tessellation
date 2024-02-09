package org.tessellation.json

import cats.effect.{IO, Resource}

import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.schema._

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import weaver.MutableIOSuite

object JsonSerializerSuite extends MutableIOSuite {

  type Res = JsonSerializer[IO]

  override def sharedResource: Resource[IO, Res] =
    JsonSerializer.forSync[IO].asResource

  test("maintains consistent key ordering") { serializer =>
    val foo = Foo("lorem", 1)
    val bar = Bar(1, "lorem")

    for {
      res1 <- serializer.serialize(foo)
      res2 <- serializer.serialize(bar)

    } yield expect.eql(res1, res2)
  }

  @derive(encoder, decoder)
  case class Foo(a: String, b: Int)
  @derive(encoder, decoder)
  case class Bar(b: Int, a: String)
}
