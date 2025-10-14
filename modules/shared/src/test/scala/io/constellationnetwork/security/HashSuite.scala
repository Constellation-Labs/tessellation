package io.constellationnetwork.security

import java.nio.charset.StandardCharsets

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

  pureTest("fromBytes produces consistent hashes") {
    val testData = "Hello, World!".getBytes(StandardCharsets.UTF_8)
    val hash1 = Hash.fromBytes(testData)
    val hash2 = Hash.fromBytes(testData)

    expect.eql(hash1, hash2)
  }

  pureTest("fromBytes produces different hashes for different inputs") {
    val data1 = "Hello, World!".getBytes(StandardCharsets.UTF_8)
    val data2 = "Hello, Universe!".getBytes(StandardCharsets.UTF_8)

    val hash1 = Hash.fromBytes(data1)
    val hash2 = Hash.fromBytes(data2)

    expect(hash1 != hash2)
  }

  pureTest("fromBytes produces valid SHA-256 hash") {
    val testData = "test".getBytes(StandardCharsets.UTF_8)
    val hash = Hash.fromBytes(testData)

    val expectedHash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    expect.eql(hash.value, expectedHash)
  }

  pureTest("fromBytes handles empty arrays") {
    val emptyData = Array.empty[Byte]
    val hash = Hash.fromBytes(emptyData)

    val expectedHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    expect.eql(hash.value, expectedHash)
  }

  test("fromBytesForSync produces consistent hashes") { implicit res =>
    val testData = "Hello, World!".getBytes(StandardCharsets.UTF_8)

    for {
      hash1 <- Hash.fromBytesForSync[IO](testData)
      hash2 <- Hash.fromBytesForSync[IO](testData)
    } yield expect.eql(hash1, hash2)
  }

  test("fromBytesForSync produces different hashes for different inputs") { implicit res =>
    val data1 = "Hello, World!".getBytes(StandardCharsets.UTF_8)
    val data2 = "Hello, Universe!".getBytes(StandardCharsets.UTF_8)

    for {
      hash1 <- Hash.fromBytesForSync[IO](data1)
      hash2 <- Hash.fromBytesForSync[IO](data2)
    } yield expect(hash1 != hash2)
  }

  test("fromBytesForSync produces valid SHA-256 hash") { implicit res =>
    val testData = "test".getBytes(StandardCharsets.UTF_8)

    Hash.fromBytesForSync[IO](testData).map { hash =>
      val expectedHash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
      expect.eql(hash.value, expectedHash)
    }
  }

  test("fromBytesForSync handles empty arrays") { implicit res =>
    val emptyData = Array.empty[Byte]

    Hash.fromBytesForSync[IO](emptyData).map { hash =>
      val expectedHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
      expect.eql(hash.value, expectedHash)
    }
  }

  test("fromBytes and fromBytesForSync produce identical results") { implicit res =>
    val testData = "Hello, World!".getBytes(StandardCharsets.UTF_8)

    Hash.fromBytesForSync[IO](testData).map { asyncHash =>
      val syncHash = Hash.fromBytes(testData)
      expect.eql(asyncHash, syncHash)
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
