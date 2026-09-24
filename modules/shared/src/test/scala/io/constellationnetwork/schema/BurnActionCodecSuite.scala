package io.constellationnetwork.schema

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact._
import io.constellationnetwork.schema.swap.{CurrencyId, SwapAmount}
import io.constellationnetwork.security.Hasher

import eu.timepit.refined.auto._
import io.circe.syntax._
import io.circe.{Json, Printer}
import weaver.SimpleIOSuite

object BurnActionCodecSuite extends SimpleIOSuite {
  private val address = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")
  private val tx = BurnTransaction(CurrencyId(address), SwapAmount(1L), address)
  private val burn: SharedArtifact = BurnAction(NonEmptyList.one(tx))
  private val printer = Printer.noSpaces.copy(sortKeys = true, dropNullValues = true)

  pureTest("JSON golden: self-burn has no delegated, DAG or opaque intent field") {
    expect.same(
      "{\"BurnAction\":{\"burnTransactions\":[{\"amount\":1,\"currencyId\":\"DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB\",\"source\":\"DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB\"}]}}",
      printer.print(burn.asJson)
    ) && expect.same(Right(burn), burn.asJson.as[SharedArtifact])
  }

  private val rejected = List(
    "DAG currency" -> tx.asJson.mapObject(_.add("currencyId", Json.Null)),
    "missing currency" -> tx.asJson.mapObject(_.remove("currencyId")),
    "delegated reference" -> tx.asJson.mapObject(_.add("allowSpendRef", Json.fromString("a" * 64))),
    "null delegated reference is not silently reinterpreted" -> tx.asJson.mapObject(_.add("allowSpendRef", Json.Null)),
    "opaque intent" -> tx.asJson.mapObject(_.add("intentHash", Json.fromString("b" * 64))),
    "destination" -> tx.asJson.mapObject(_.add("destination", address.asJson)),
    "zero" -> tx.asJson.mapObject(_.add("amount", Json.fromLong(0L))),
    "negative" -> tx.asJson.mapObject(_.add("amount", Json.fromLong(Long.MinValue))),
    "overflow" -> tx.asJson.mapObject(_.add("amount", Json.fromBigInt(BigInt(Long.MaxValue) + 1)))
  )
  rejected.foreach {
    case (name, transaction) =>
      pureTest(s"strict nested SharedArtifact decoder rejects $name") {
        val json = Json.obj("BurnAction" -> Json.obj("burnTransactions" -> Json.arr(transaction)))
        expect(transaction.as[BurnTransaction].isLeft) && expect(json.as[SharedArtifact].isLeft)
      }
  }

  pureTest("empty actions and unknown action fields reject") {
    expect(Json.obj("BurnAction" -> Json.obj("burnTransactions" -> Json.arr())).as[SharedArtifact].isLeft) &&
    expect(Json.obj("BurnAction" -> Json.obj("burnTransactions" -> Json.arr(tx.asJson), "extra" -> Json.True)).as[SharedArtifact].isLeft)
  }

  test("production Brotli JSON round-trip and hash retain Long.MaxValue exactly") {
    for {
      implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      maximum = BurnAction(NonEmptyList.one(tx.copy(amount = SwapAmount(Long.MaxValue)))): SharedArtifact
      bytes <- serializer.serialize(maximum)
      decoded <- serializer.deserialize[SharedArtifact](bytes)
      originalHash <- Hasher.forJson[IO].hash(maximum)
      restored <- IO.fromEither(decoded)
      restoredHash <- Hasher.forJson[IO].hash(restored)
    } yield expect.same(maximum, restored) && expect.same(originalHash, restoredHash)
  }

  test("production hash goldens for self-burn and the unchanged ordinary SpendAction") {
    for {
      implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      spend = SpendAction(
        NonEmptyList.one(SpendTransaction(None, Some(CurrencyId(address)), SwapAmount(1L), address, address))
      ): SharedArtifact
      burnHash <- Hasher.forJson[IO].hash(burn)
      spendHash <- Hasher.forJson[IO].hash(spend)
    } yield
      expect.same("f7c668653af52d1ccf531c660a594ce8d7edf63ff24db1b6af5b56b3668f3ff2", burnHash.value) &&
        expect.same("da2a30fd22c6246cc76bfcc50d44d13341878cc79ae660b71252b739249f1d5a", spendHash.value)
  }
}
