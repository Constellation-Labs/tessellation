package io.constellationnetwork.security

import cats.Show
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.annotation.nowarn

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.{GlobalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.shared.{SharedKryoRegistrationId, sharedKryoRegistrar}

import derevo.circe.magnolia.encoder
import derevo.derive
import eu.timepit.refined.auto._
import io.circe.Encoder
import org.scalacheck.{Arbitrary, Gen}
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object HashSuite extends MutableIOSuite with Checkers {

  type Res = HasherSelector[IO]

  val registrar: Map[Class[_], SharedKryoRegistrationId] = sharedKryoRegistrar ++ Map(
    classOf[Foo] -> 638,
    classOf[Bar] -> 639
  )

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync(registrar).flatMap { implicit kryo =>
      JsonSerializer.forSync[IO].asResource.map { implicit json =>
        HasherSelector.forSync[IO](
          Hasher.forJson[IO],
          Hasher.forKryo[IO],
          hashSelect = new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = KryoHash }
        )
      }
    }

  test("ensure backward compatibility") { implicit res =>
    def oldHash = Hash("6512e0fdd9e2b870ff6124b86744ad8e1eedb7cd4281fd7b9a36a0457e1bfdcb")

    def genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)

    res.forOrdinal(genesis.ordinal)(implicit hasher => hasher.compare(genesis, oldHash).map(expect(_)))
  }

  test("ensure hash stability when a new optional field is empty") { implicit res =>
    @derive(encoder)
    case class Test(a: Int)
    @derive(encoder)
    case class TestUpdated(a: Int, b: Option[String])

    val test = Test(2)
    val testUpdated = TestUpdated(2, None)

    res.withCurrent(implicit hasher => (hasher.hash(test), hasher.hash(testUpdated)).mapN(expect.eql(_, _)))
  }

  test("ensure guava/JSA compatibility") {
    implicit val byteArrayShow: Show[Array[Byte]] = Show.show(a => Hex.fromBytes(a).toString)

    val byte = Arbitrary.arbitrary[Byte]
    val bytes = Gen.listOfN(1024, byte).map(_.toArray)

    forall(bytes) { data =>
      @nowarn
      val hashCode = Hash.hashCodeFromBytes(data)
      val sha256Digest = Hash.sha256DigestFromBytes(data)
      expect.eql(hashCode.toString, sha256Digest.toHexString)
    }
  }

  @derive(encoder)
  case class Foo(a: Int, b: String)

  @derive(encoder)
  case class Bar(b: String, a: Int) extends Encodable[(Int, String)] {
    def toEncode: (Int, String) = (a, b)
    def jsonEncoder: Encoder[(Int, String)] = implicitly
  }
}
