package io.constellationnetwork.json

import java.nio.charset.StandardCharsets.UTF_8

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.schema.GlobalIncrementalSnapshot
import io.constellationnetwork.schema.swap.AllowSpend
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import weaver.{Expectations, MutableIOSuite}

object JsonBrotliCompatibilitySuite extends MutableIOSuite {
  type Res = (JsonSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] =
    SecurityProvider.forAsync[IO].flatMap { provider =>
      Resource.eval(JsonSerializer.forSync[IO]).map((_, provider))
    }

  private def bytes(name: String): IO[Array[Byte]] = IO.blocking {
    val stream = Option(getClass.getResourceAsStream(s"/brotli-compatibility/$name"))
      .getOrElse(throw new IllegalArgumentException(s"Missing fixture: $name"))
    try stream.readAllBytes()
    finally stream.close()
  }

  private def fixture(name: String): IO[Json] =
    bytes(name).flatMap(b => parse(new String(b, UTF_8)).liftTo[IO])

  private def verifyWallet[A: Encoder: Decoder](
    name: String,
    serializer: JsonSerializer[IO],
    provider: SecurityProvider[IO]
  ): IO[Expectations] = {
    implicit val json: JsonSerializer[IO] = serializer
    implicit val securityProvider: SecurityProvider[IO] = provider
    implicit val hasher: Hasher[IO] = Hasher.forJson[IO]

    for {
      input <- fixture(name)
      signed <- input.hcursor.get[Signed[A]]("signed").liftTo[IO]
      compressedHex <- input.hcursor.get[String]("compressedHex").liftTo[IO]
      expectedHash <- input.hcursor.get[String]("hash").liftTo[IO]
      expectedBytes = compressedHex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
      encoded <- serializer.serialize(signed.value)
      hash <- hasher.hash(signed.value)
      valid <- SignedValidator.make[IO].validateSignatures(signed)
      decoded <- serializer.deserialize[A](expectedBytes)
    } yield
      expect.same(expectedBytes.toList, encoded.toList) &&
        expect.same(expectedHash, hash.value) &&
        expect(valid.isValid) &&
        expect.same(Right(signed.value.asJson), decoded.map(_.asJson))
  }

  test("the mainnet default serializer preserves key order, null fields and exact encoded bytes") { _ =>
    for {
      input <- fixture("default-printer.json")
      value <- input.hcursor.get[Json]("value").liftTo[IO]
      compressedHex <- input.hcursor.get[String]("compressedHex").liftTo[IO]
      expectedBytes = compressedHex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
      serializer <- JsonBrotliBinarySerializer.forSync[IO]
      encoded <- serializer.serialize(value)
      decoded <- serializer.deserialize[Json](expectedBytes)
    } yield expect.same(expectedBytes.toList, encoded.toList) && expect.same(Right(value), decoded)
  }

  test("mainnet snapshot retains its exact compressed bytes, hash, signatures and decoded value") {
    case (serializer, provider) =>
      implicit val json: JsonSerializer[IO] = serializer
      implicit val securityProvider: SecurityProvider[IO] = provider
      implicit val hasher: Hasher[IO] = Hasher.forJson[IO]

      for {
        input <- fixture("mainnet-snapshot.json")
        snapshot <- input.as[Signed[GlobalIncrementalSnapshot]].liftTo[IO]
        provenance <- fixture("provenance.json")
        expectedHash <- provenance.hcursor.get[String]("expectedSnapshotHash").liftTo[IO]
        expectedBytes <- bytes("mainnet-snapshot-value.br")
        encoded <- serializer.serialize(snapshot.value)
        hash <- hasher.hash(snapshot.value)
        valid <- SignedValidator.make[IO].validateSignatures(snapshot)
        decoded <- serializer.deserialize[GlobalIncrementalSnapshot](expectedBytes)
      } yield
        expect.same(expectedBytes.toList, encoded.toList) &&
          expect.same(expectedHash, hash.value) &&
          expect(valid.isValid) &&
          expect.same(Right(snapshot.value.asJson), decoded.map(_.asJson))
  }

  test("mainnet state-channel binary decodes to the native decoder's recorded JSON") {
    case (serializer, _) =>
      for {
        input <- fixture("mainnet-snapshot.json")
        provenance <- fixture("provenance.json")
        address <- provenance.hcursor.get[String]("stateChannelAddress").liftTo[IO]
        index <- provenance.hcursor.get[Int]("stateChannelIndex").liftTo[IO]
        content <- input.hcursor
          .downField("value")
          .downField("stateChannelSnapshots")
          .downField(address)
          .downN(index)
          .downField("value")
          .get[Array[Byte]]("content")
          .liftTo[IO]
        expected <- fixture("state-channel-decoded.json")
        decoded <- serializer.deserialize[Json](content)
      } yield expect.same(Right(expected), decoded)
  }

  test("dag4.js allow-spend preserves exact bytes and its signature (including the full-upgrade counterexample)") {
    case (serializer, provider) => verifyWallet[AllowSpend]("allow-spend.json", serializer, provider)
  }

  test("dag4.js token-lock preserves exact bytes and its signature") {
    case (serializer, provider) => verifyWallet[TokenLock]("token-lock.json", serializer, provider)
  }

  test("malformed, truncated and trailing Brotli data return Left without failing the effect") {
    case (serializer, _) =>
      val random = new scala.util.Random(1789L)
      val malformed = List.fill(200) {
        val input = new Array[Byte](random.nextInt(100) + 1)
        random.nextBytes(input)
        input
      }
      for {
        valid <- serializer.serialize(Json.obj("test" -> Json.True))
        truncated = valid.indices.map(n => valid.take(n)).toList
        trailing = List(valid :+ 0.toByte, valid ++ valid)
        results <- (Array.emptyByteArray :: malformed ::: truncated ::: trailing).traverse(serializer.deserialize[Json](_).attempt)
      } yield expect(results.forall(_.exists(_.isLeft)))
  }

  test("a JSON type mismatch returns Left") {
    case (serializer, _) =>
      for {
        encoded <- serializer.serialize(Json.fromString("not an integer"))
        decoded <- serializer.deserialize[Int](encoded)
      } yield expect(decoded.isLeft)
  }
}
