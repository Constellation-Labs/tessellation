package org.tessellation.security

import cats.effect.{IO, Resource}
import cats.syntax.all._

import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.{GlobalSnapshot, SnapshotOrdinal}
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
      JsonSerializer.forSync[IO].asResource.map { implicit json =>
        Hasher.forSync[IO](new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash })
      }
    }

  test("ensure backward compatibility") { implicit res =>
    def oldHash = Hash("6512e0fdd9e2b870ff6124b86744ad8e1eedb7cd4281fd7b9a36a0457e1bfdcb")

    def genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)

    res.compare(genesis, oldHash).map(expect(_))
  }

  test("ensure hash stability when a new optional field is empty") { implicit res =>
    @derive(encoder)
    case class Test(a: Int)
    @derive(encoder)
    case class TestUpdated(a: Int, b: Option[String])

    val test = Test(2)
    val testUpdated = TestUpdated(2, None)

    (res.hash(test), res.hash(testUpdated)).mapN(expect.eql(_, _))
  }

  @derive(encoder)
  case class Foo(a: Int, b: String)

  @derive(encoder)
  case class Bar(b: String, a: Int) extends Encodable[(Int, String)] {
    def toEncode: (Int, String) = (a, b)
    def jsonEncoder: Encoder[(Int, String)] = implicitly
  }
}
