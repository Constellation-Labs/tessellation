package org.tessellation.rosetta.server

import java.util.concurrent.TimeUnit

import cats.effect.unsafe.implicits.global
import cats.effect.{Async, IO, Resource}

import scala.concurrent.duration.Duration
import scala.io.Source

import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.rosetta.server.runner.runnerKryoRegistrar
import org.tessellation.sdk.config.types.{HttpClientConfig, HttpServerConfig}
import org.tessellation.sdk.resources.MkHttpServer.ServerName
import org.tessellation.sdk.resources.{MkHttpClient, MkHttpServer}
import org.tessellation.security.SecurityProvider
import org.tessellation.shared.sharedKryoRegistrar

import com.comcast.ip4s.{Host, Port}
import io.circe.parser.parse
import io.circe.{Json, JsonNumber}
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.server.Server
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import suite.ResourceSuite
import weaver.scalacheck.Checkers

object RosettaConstructionSuite extends ResourceSuite with Checkers {
  override type Res = (Resource[IO, Server], Resource[IO, Client[IO]])
  override def sharedResource: Resource[IO, Res] = {
    val server = serverResource()
    val client = MkHttpClient
      .forAsync[IO]
      .newEmber(HttpClientConfig(Duration(5, TimeUnit.SECONDS), Duration(10, TimeUnit.SECONDS)))

    IO.pure((server: Resource[IO, Server], client: Resource[IO, Client[IO]])).asResource
  }

  test("Construction test") { implicit resources: Res =>
    val serverObj = resources._1
    val httpClientObj = resources._2

    serverObj.use { _ =>
      httpClientObj.use { client =>
        val jsonString = Source.fromResource("rosetta.test_transactions.json").getLines().mkString
        val cursor = parse(jsonString).getOrElse(Json.Null).hcursor
        val params = cursor.downField("item").as[List[Json]].getOrElse(List.empty[Json])

        val testResults = params.map { testEntry =>
          val entryCursor = testEntry.hcursor
          val payloadString =
            entryCursor.downField("request").downField("body").downField("raw").as[String].getOrElse("{}")
          val payload = parse(payloadString).getOrElse(Json.Null)

          val urlString = "http://%s".format(
            entryCursor.downField("request").downField("url").downField("raw").as[String].getOrElse("")
          )
          val expectedResponseString = entryCursor.downField("response").as[String].getOrElse("")

          Uri.fromString(urlString).map { uri =>
            val req = Request[IO](method = Method.POST, uri = uri)
            val reqPayload = req.withEntity(payload)

            val responseString =
              client.run(req = reqPayload).use(response => response.bodyText.compile.string).unsafeRunSync()

            var expectedResponseJson = parse(expectedResponseString).getOrElse(Json.Null)
            var actualResponseJson = parse(responseString).getOrElse(Json.Null)

            // Sanitize/normalize the responses.
            expectedResponseJson = cleanJson(expectedResponseJson)
            actualResponseJson = cleanJson(actualResponseJson)

            expectedResponseJson == actualResponseJson
          } match {
            case Left(_)      => false
            case Right(value) => value
          }
        }

        IO.pure(forEach(testResults)(b => expect(b)))
      }
    }
  }

  private def cleanJson(json: Json) = {
    var jsonObj = json

    if (jsonObj.hcursor
          .downField("current_block_timestamp")
          .as[Json]
          .getOrElse(Json.Null) != Json.Null) {
      jsonObj = jsonObj.hcursor
        .downField("current_block_timestamp")
        .withFocus(_.mapNumber(_ => JsonNumber.fromDecimalStringUnsafe("0")))
        .top
        .getOrElse(Json.Null)
    }

    if (jsonObj.hcursor
          .downField("block")
          .downField("timestamp")
          .as[Json]
          .getOrElse(Json.Null) != Json.Null) {
      jsonObj = jsonObj.hcursor
        .downField("block")
        .downField("timestamp")
        .withFocus(_.mapNumber(_ => JsonNumber.fromDecimalStringUnsafe("0")))
        .top
        .getOrElse(Json.Null)
    }

    if (jsonObj.hcursor
          .downField("block")
          .downField("block_identifier")
          .downField("hash")
          .as[Json]
          .getOrElse(Json.Null) != Json.Null) {
      jsonObj = jsonObj.hcursor
        .downField("block")
        .downField("block_identifier")
        .downField("hash")
        .withFocus(_.mapString(_ => ""))
        .top
        .getOrElse(Json.Null)
    }

    if (jsonObj.hcursor
          .downField("block")
          .downField("parent_block_identifier")
          .downField("hash")
          .as[Json]
          .getOrElse(Json.Null) != Json.Null) {
      jsonObj = jsonObj.hcursor
        .downField("block")
        .downField("parent_block_identifier")
        .downField("hash")
        .withFocus(_.mapString(_ => ""))
        .top
        .getOrElse(Json.Null)
    }

    if (jsonObj.hcursor
          .downField("block_identifier")
          .downField("hash")
          .as[Json]
          .getOrElse(Json.Null) != Json.Null) {
      jsonObj = jsonObj.hcursor
        .downField("block_identifier")
        .downField("hash")
        .withFocus(_.mapString(_ => ""))
        .top
        .getOrElse(Json.Null)
    }

    if (jsonObj.hcursor
          .downField("block")
          .downField("transactions")
          .as[List[Json]]
          .getOrElse(List.empty) != List.empty) {
      jsonObj = jsonObj.hcursor
        .downField("block")
        .downField("transactions")
        .withFocus(_.mapArray(_ => Vector.empty))
        .top
        .getOrElse(Json.Null)
    }

    if (jsonObj.hcursor
          .downField("details")
          .downField("details")
          .as[List[Json]]
          .getOrElse(List.empty) != List.empty) {
      jsonObj = jsonObj.hcursor
        .downField("details")
        .downField("details")
        .withFocus(_.mapArray(_ => Vector.empty))
        .top
        .getOrElse(Json.Null)
    }

    if (jsonObj.hcursor
          .downField("transaction")
          .downField("transaction_identifier")
          .as[Json]
          .getOrElse(Json.Null) != Json.Null) {
      jsonObj = jsonObj.hcursor
        .downField("transaction")
        .downField("transaction_identifier")
        .downField("hash")
        .withFocus(_.mapString(_ => ""))
        .top
        .getOrElse(Json.Null)
    }

    if (jsonObj.hcursor
          .downField("current_block_identifier")
          .as[Json]
          .getOrElse(Json.Null) != Json.Null) {
      jsonObj = jsonObj.hcursor
        .downField("current_block_identifier")
        .downField("hash")
        .withFocus(_.mapString(_ => ""))
        .top
        .getOrElse(Json.Null)
    }

    if (jsonObj.hcursor
          .downField("genesis_block_identifier")
          .as[Json]
          .getOrElse(Json.Null) != Json.Null) {
      jsonObj = jsonObj.hcursor
        .downField("genesis_block_identifier")
        .downField("hash")
        .withFocus(_.mapString(_ => ""))
        .top
        .getOrElse(Json.Null)
    }

    if (jsonObj.hcursor
          .downField("oldest_block_identifier")
          .as[Json]
          .getOrElse(Json.Null) != Json.Null) {
      jsonObj = jsonObj.hcursor
        .downField("oldest_block_identifier")
        .downField("hash")
        .withFocus(_.mapString(_ => ""))
        .top
        .getOrElse(Json.Null)
    }

    if (jsonObj.hcursor
          .downField("transaction_identifier")
          .as[Json]
          .getOrElse(Json.Null) != Json.Null) {
      jsonObj = jsonObj.hcursor
        .downField("transaction_identifier")
        .downField("hash")
        .withFocus(_.mapString(_ => ""))
        .top
        .getOrElse(Json.Null)
    }

    jsonObj
  }

  private def serverResource() = {
    implicit val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
    import org.tessellation.ext.kryo._
    val registrar = org.tessellation.dag.dagSharedKryoRegistrar.union(sharedKryoRegistrar).union(runnerKryoRegistrar)
    SecurityProvider
      .forAsync[IO]
      .flatMap { implicit sp =>
        KryoSerializer
          .forAsync[IO](registrar)
          .flatMap { implicit kryo =>
            val value = MockData.mockup.genesis.hash.toOption.get
            MockData.mockup.genesisHash = value.value
            MockData.mockup.currentBlockHash = value
            MockData.mockup.blockToHash(MockData.mockup.genesis) = value
            val value1 = kryo.serialize(examples.transaction)
            value1.left.map(t => throw t)
            val http = new RosettaRoutes[IO]()(Async[IO], kryo, sp)
            val publicApp: HttpApp[IO] = http.allRoutes.orNotFound
            MkHttpServer[IO].newEmber(
              ServerName("public"),
              HttpServerConfig(Host.fromString("0.0.0.0").get, Port.fromInt(8080).get),
              publicApp
            )
          }
      }
  }
}
