package io.constellationnetwork.node.shared.http.p2p.middlewares

import cats.data.{Kleisli, OptionT}
import cats.effect.IO

import scala.concurrent.duration._

import org.http4s._
import org.typelevel.ci.CIString
import weaver.SimpleIOSuite

object PerIpRateLimitMiddlewareSuite extends SimpleIOSuite {

  private val okRoute: HttpRoutes[IO] =
    Kleisli(_ => OptionT.some[IO](Response[IO](status = Status.Ok)))

  private def reqFromIp(ipStr: String): Request[IO] =
    Request[IO](
      method = Method.GET,
      uri = Uri.unsafeFromString("/some/path"),
      headers = Headers(Header.Raw(CIString("X-Forwarded-For"), ipStr))
    )

  test("first request under cap is accepted (200)") {
    for {
      mw <- PerIpRateLimitMiddleware[IO](maxRequestsPerWindow = 5, windowDuration = 1.minute)
      wrapped = mw(okRoute)
      resp <- wrapped(reqFromIp("203.0.113.10")).getOrElse(Response.notFound[IO])
    } yield expect(resp.status == Status.Ok, s"expected 200, got ${resp.status}")
  }

  test("requests beyond cap within window are rejected with 429") {
    for {
      mw <- PerIpRateLimitMiddleware[IO](maxRequestsPerWindow = 2, windowDuration = 1.minute)
      wrapped = mw(okRoute)
      r1 <- wrapped(reqFromIp("203.0.113.20")).getOrElse(Response.notFound[IO])
      r2 <- wrapped(reqFromIp("203.0.113.20")).getOrElse(Response.notFound[IO])
      r3 <- wrapped(reqFromIp("203.0.113.20")).getOrElse(Response.notFound[IO])
    } yield
      expect(r1.status == Status.Ok, "first request accepted")
        .and(expect(r2.status == Status.Ok, "second request accepted"))
        .and(expect(r3.status == Status.TooManyRequests, s"third request must be 429, got ${r3.status}"))
  }

  test("different IPs have independent counters") {
    for {
      mw <- PerIpRateLimitMiddleware[IO](maxRequestsPerWindow = 1, windowDuration = 1.minute)
      wrapped = mw(okRoute)
      a1 <- wrapped(reqFromIp("203.0.113.30")).getOrElse(Response.notFound[IO])
      a2 <- wrapped(reqFromIp("203.0.113.30")).getOrElse(Response.notFound[IO])
      b1 <- wrapped(reqFromIp("203.0.113.31")).getOrElse(Response.notFound[IO])
    } yield
      expect(a1.status == Status.Ok, "IP A first OK")
        .and(expect(a2.status == Status.TooManyRequests, "IP A second rejected"))
        .and(expect(b1.status == Status.Ok, "IP B unaffected by IP A's overrun"))
  }

  test("allowlist: matching IP bypasses the counter, never gets 429") {
    val streaming = "13.57.169.30"
    for {
      mw <- PerIpRateLimitMiddleware[IO](
        maxRequestsPerWindow = 1,
        windowDuration = 1.minute,
        allowlist = Set(streaming)
      )
      wrapped = mw(okRoute)
      r1 <- wrapped(reqFromIp(streaming)).getOrElse(Response.notFound[IO])
      r2 <- wrapped(reqFromIp(streaming)).getOrElse(Response.notFound[IO])
      r3 <- wrapped(reqFromIp(streaming)).getOrElse(Response.notFound[IO])
    } yield
      expect(r1.status == Status.Ok, "allowlisted IP r1 accepted")
        .and(expect(r2.status == Status.Ok, "allowlisted IP r2 accepted (would normally be 429)"))
        .and(expect(r3.status == Status.Ok, "allowlisted IP r3 accepted"))
  }

  test("allowlist: non-allowlisted IP still gets rate-limited normally") {
    for {
      mw <- PerIpRateLimitMiddleware[IO](
        maxRequestsPerWindow = 1,
        windowDuration = 1.minute,
        allowlist = Set("13.57.169.30")
      )
      wrapped = mw(okRoute)
      a1 <- wrapped(reqFromIp("198.51.100.1")).getOrElse(Response.notFound[IO])
      a2 <- wrapped(reqFromIp("198.51.100.1")).getOrElse(Response.notFound[IO])
    } yield
      expect(a1.status == Status.Ok, "first request accepted")
        .and(expect(a2.status == Status.TooManyRequests, "second request rejected — allowlist does not apply"))
  }
}
