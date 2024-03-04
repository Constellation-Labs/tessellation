package org.tessellation.node.shared.http.routes

import cats.effect._
import cats.syntax.applicative._
import cats.syntax.validated._

import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.infrastructure.snapshot.services.CurrencyMessageService
import org.tessellation.node.shared.infrastructure.snapshot.services.CurrencyMessageService.{
  CurrencyMessageServiceErrorOr,
  OlderOrdinalError
}
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.currencyMessage.{CurrencyMessage, MessageOrdinal, MessageType}
import org.tessellation.schema.generators.{addressGen, signedOf}
import org.tessellation.security._
import org.tessellation.security.signature.Signed
import org.tessellation.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.NonNegLong
import io.circe.syntax.EncoderOps
import org.http4s.Method.POST
import org.http4s.Status.{BadRequest, NoContent}
import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.dsl.io._
import org.http4s.syntax.literals._
import org.scalacheck.Gen
import suite.HttpSuite

object CurrencyMessageRoutesSuite extends HttpSuite {
  val route: Request[IO] = POST(uri"currency/message")

  val currencyMessageGen: Gen[Signed[CurrencyMessage]] =
    signedOf(
      for {
        typ <- Gen.oneOf(MessageType.values)
        addr <- addressGen
        ord = MessageOrdinal(NonNegLong.unsafeFrom(0L))
      } yield CurrencyMessage(typ, addr, ord)
    )

  test("POST offers a valid CurrencyMessage") {
    forall(currencyMessageGen) { message =>
      val service = mockService(offerFn = valid(message))
      makeCurrencyMessageRoutes(service).use { routes =>
        expectHttpStatus(routes, route.withEntity(message.asJson))(NoContent)
      }
    }
  }

  test("POST offers an invalid CurrencyMessage") {
    forall(currencyMessageGen) { message =>
      val service = mockService(offerFn = invalid(message))
      makeCurrencyMessageRoutes(service).use { routes =>
        expectHttpStatus(routes, route.withEntity(message.asJson))(BadRequest)
      }
    }
  }

  def makeCurrencyMessageRoutes(service: CurrencyMessageService[IO]): Resource[IO, HttpRoutes[IO]] =
    for {
      implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
      implicit0(sp: SecurityProvider[IO]) <- SecurityProvider.forAsync[IO]
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
      implicit0(h: Hasher[IO]) = Hasher.forSync[IO](new HashSelect {
        def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash
      })
    } yield new CurrencyMessageRoutes[IO](service).publicRoutes

  def valid[A](expected: A): A => IO[CurrencyMessageServiceErrorOr[Unit]] =
    actual =>
      if (actual == expected)
        ().validNec.pure[IO]
      else
        IO.raiseError(new Exception("unexpected arg"))

  def invalid[A](expected: A): A => IO[CurrencyMessageServiceErrorOr[Unit]] =
    actual =>
      if (actual == expected)
        OlderOrdinalError.invalidNec.pure[IO]
      else
        IO.raiseError(new Exception("unexpected arg"))

  def mockService(
    offerFn: Signed[CurrencyMessage] => IO[CurrencyMessageServiceErrorOr[Unit]] = _ => IO.raiseError(new Exception("offer call unexpected"))
  ): CurrencyMessageService[IO] =
    new CurrencyMessageService[IO] {
      def offer(message: Signed[CurrencyMessage]): IO[CurrencyMessageServiceErrorOr[Unit]] = offerFn(message)
    }
}
