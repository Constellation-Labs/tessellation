package org.tessellation.security

import cats.effect.{IO, Resource}

import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.json.JsonHashSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.GlobalSnapshot
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.security.hash.Hash
import org.tessellation.shared.{SharedKryoRegistrationId, sharedKryoRegistrar}

import derevo.circe.magnolia.encoder
import derevo.derive
import eu.timepit.refined.auto._
import io.circe.Encoder
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object HashSuite extends MutableIOSuite with Checkers {

  type Res = Hasher[IO]

  val registrar: Map[Class[_], SharedKryoRegistrationId] = sharedKryoRegistrar ++ Map(
    classOf[Foo] -> 638,
    classOf[Bar] -> 639
  )

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync(registrar).flatMap { implicit kryo =>
      JsonHashSerializer.forSync[IO].asResource.map { implicit json =>
        Hasher.forSync[IO]
      }
    }

  test("ensure backward compatibility") { implicit res =>
    def oldHash = Hash("6512e0fdd9e2b870ff6124b86744ad8e1eedb7cd4281fd7b9a36a0457e1bfdcb")

    def genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
    def result = res.hash(genesis)

    result.map(expect.eql(_, oldHash))
  }

  @derive(encoder)
  case class Foo(a: Int, b: String)

  @derive(encoder)
  case class Bar(b: String, a: Int) extends Encodable[(Int, String)] {
    def toEncode: (Int, String) = (a, b)
    def jsonEncoder: Encoder[(Int, String)] = implicitly
  }
}
