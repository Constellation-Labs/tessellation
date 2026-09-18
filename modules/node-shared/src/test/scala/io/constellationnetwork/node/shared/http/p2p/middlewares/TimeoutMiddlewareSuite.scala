package io.constellationnetwork.node.shared.http.p2p.middlewares

import java.util.concurrent.TimeoutException

import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref, Resource}

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.http.p2p.middlewares.TimeoutMiddleware.withTimeout

import fs2.Stream
import org.http4s.client.Client
import org.http4s.{Request, Response}
import weaver.SimpleIOSuite

object TimeoutMiddlewareSuite extends SimpleIOSuite {
  private val request = Request[IO]()
  private val timeout = 5.seconds

  test("response acquisition retains its own timeout") {
    TestControl.executeEmbed {
      val transport = Client[IO](_ => Resource.eval(IO.sleep(6.seconds).as(Response[IO]())))
      for {
        start <- IO.monotonic
        result <- withTimeout(transport, timeout).run(request).use(_.body.compile.drain).attempt
        elapsed <- IO.monotonic.map(_ - start)
      } yield expect(result.left.exists(_.isInstanceOf[TimeoutException])) && expect.same(elapsed, timeout)
    }
  }

  test("an acquired response times out between body chunks and is released") {
    TestControl.executeEmbed {
      for {
        received <- Ref.of[IO, Int](0)
        released <- Ref.of[IO, Int](0)
        body = Stream.emit(1.toByte).covary[IO] ++ Stream.never[IO]
        transport = Client[IO] { _ =>
          Resource.make(IO.pure(Response[IO]().withBodyStream(body)))(_ => released.update(_ + 1))
        }
        result <- withTimeout(transport, timeout)
          .stream(request)
          .flatMap(_.body)
          .evalTap(_ => received.update(_ + 1))
          .compile
          .drain
          .attempt
        receivedCount <- received.get
        releasedCount <- released.get
      } yield
        expect(result.left.exists(_.isInstanceOf[TimeoutException])) &&
          expect.same(receivedCount, 1) && expect.same(releasedCount, 1)
    }
  }

  test("the body deadline resets when each chunk arrives") {
    TestControl.executeEmbed {
      val body = Stream.emits(List(1.toByte, 2.toByte, 3.toByte)).covary[IO].evalMap(byte => IO.sleep(4.seconds).as(byte))
      val transport = Client[IO](_ => Resource.pure(Response[IO]().withBodyStream(body)))

      for {
        start <- IO.monotonic
        result <- withTimeout(transport, timeout).stream(request).flatMap(_.body).compile.toList
        elapsed <- IO.monotonic.map(_ - start)
      } yield expect.same(result, List(1.toByte, 2.toByte, 3.toByte)) && expect.same(elapsed, 12.seconds)
    }
  }

  test("a separate total response deadline expires even while chunks keep arriving") {
    TestControl.executeEmbed {
      for {
        received <- Ref.of[IO, Int](0)
        released <- Ref.of[IO, Int](0)
        body = Stream.repeatEval(IO.sleep(1.second).as(1.toByte))
        transport = Client[IO] { _ =>
          Resource.make(IO.pure(Response[IO]().withBodyStream(body)))(_ => released.update(_ + 1))
        }
        start <- IO.monotonic
        result <- withTimeout(transport, timeout, 10.seconds)
          .stream(request)
          .flatMap(_.body)
          .evalTap(_ => received.update(_ + 1))
          .compile
          .drain
          .attempt
        elapsed <- IO.monotonic.map(_ - start)
        receivedCount <- received.get
        releasedCount <- released.get
      } yield
        expect(result.left.exists(_.isInstanceOf[TimeoutException])) &&
          expect.same(elapsed, 10.seconds) && expect(receivedCount > 1) && expect.same(releasedCount, 1)
    }
  }

  test("slow downstream processing does not consume the body-idle deadline") {
    TestControl.executeEmbed {
      val transport = Client[IO](_ => Resource.pure(Response[IO]().withBodyStream(Stream.emit(1.toByte).covary[IO])))

      for {
        result <- withTimeout(transport, timeout)
          .stream(request)
          .flatMap(_.body)
          .evalMap(byte => IO.sleep(6.seconds).as(byte))
          .compile
          .toList
      } yield expect.same(result, List(1.toByte))
    }
  }
}
