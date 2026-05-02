package io.constellationnetwork.node.shared.http.p2p.middlewares

import cats.data.{Kleisli, OptionT}
import cats.effect.IO

import scala.concurrent.duration._

import org.http4s._
import org.http4s.headers.`Content-Length`
import org.typelevel.ci.CIString
import weaver.SimpleIOSuite

object PerIpBandwidthLimitMiddlewareSuite extends SimpleIOSuite {

  // A trivial inner route that always responds 200 with the configured Content-Length.
  private def fixedSizeRoute(bytes: Long): HttpRoutes[IO] =
    Kleisli(_ =>
      OptionT.some[IO](
        Response[IO](status = Status.Ok, headers = Headers(`Content-Length`.unsafeFromLong(bytes)))
      )
    )

  private def reqFromIp(ipStr: String, path: String = "/some/path"): Request[IO] =
    Request[IO](
      method = Method.GET,
      uri = Uri.unsafeFromString(path),
      headers = Headers(Header.Raw(CIString("X-Forwarded-For"), ipStr))
    )

  test("first request under cap is accepted (200)") {
    val cap = 100L * 1024L * 1024L // 100 MB
    val window = 1.minute
    val responseSize = 50L * 1024L * 1024L // 50 MB
    for {
      mw <- PerIpBandwidthLimitMiddleware[IO](cap, window)
      wrapped = mw(fixedSizeRoute(responseSize))
      resp <- wrapped(reqFromIp("203.0.113.10")).getOrElse(Response.notFound[IO])
    } yield expect(resp.status == Status.Ok, s"expected 200, got ${resp.status}")
  }

  test("two large requests within window: second exceeds cap and is rejected with 429") {
    val cap = 100L * 1024L * 1024L
    val window = 1.minute
    val responseSize = 60L * 1024L * 1024L // 60 MB; two = 120 MB > cap
    for {
      mw <- PerIpBandwidthLimitMiddleware[IO](cap, window)
      wrapped = mw(fixedSizeRoute(responseSize))
      r1 <- wrapped(reqFromIp("203.0.113.20")).getOrElse(Response.notFound[IO])
      r2 <- wrapped(reqFromIp("203.0.113.20")).getOrElse(Response.notFound[IO])
    } yield
      expect(r1.status == Status.Ok, "first request fits in cap").and(
        expect(r2.status == Status.TooManyRequests, s"second request must be 429, got ${r2.status}")
      )
  }

  test("different IPs have independent budgets") {
    val cap = 100L * 1024L * 1024L
    val window = 1.minute
    val responseSize = 60L * 1024L * 1024L
    for {
      mw <- PerIpBandwidthLimitMiddleware[IO](cap, window)
      wrapped = mw(fixedSizeRoute(responseSize))
      a1 <- wrapped(reqFromIp("203.0.113.30")).getOrElse(Response.notFound[IO])
      a2 <- wrapped(reqFromIp("203.0.113.30")).getOrElse(Response.notFound[IO])
      b1 <- wrapped(reqFromIp("203.0.113.31")).getOrElse(Response.notFound[IO])
    } yield
      expect(a1.status == Status.Ok, "IP A first request OK")
        .and(
          expect(a2.status == Status.TooManyRequests, "IP A second request rejected")
        )
        .and(
          expect(b1.status == Status.Ok, "IP B unaffected by IP A's overrun")
        )
  }

  test("allowlist: matching IP bypasses the bandwidth cap, never gets 429") {
    val streaming = "13.57.169.30"
    val cap = 100L * 1024L * 1024L
    val window = 1.minute
    val responseSize = 80L * 1024L * 1024L // single response < cap; two would exceed
    for {
      mw <- PerIpBandwidthLimitMiddleware[IO](cap, window, allowlist = Set(streaming))
      wrapped = mw(fixedSizeRoute(responseSize))
      r1 <- wrapped(reqFromIp(streaming)).getOrElse(Response.notFound[IO])
      r2 <- wrapped(reqFromIp(streaming)).getOrElse(Response.notFound[IO])
      r3 <- wrapped(reqFromIp(streaming)).getOrElse(Response.notFound[IO])
    } yield
      expect(r1.status == Status.Ok, "allowlisted r1 accepted")
        .and(expect(r2.status == Status.Ok, "allowlisted r2 accepted (would normally be 429)"))
        .and(expect(r3.status == Status.Ok, "allowlisted r3 accepted"))
  }

  test("allowlist: non-matching IP still rate-limited normally") {
    val cap = 100L * 1024L * 1024L
    val window = 1.minute
    val responseSize = 80L * 1024L * 1024L
    for {
      mw <- PerIpBandwidthLimitMiddleware[IO](cap, window, allowlist = Set("13.57.169.30"))
      wrapped = mw(fixedSizeRoute(responseSize))
      a1 <- wrapped(reqFromIp("198.51.100.50")).getOrElse(Response.notFound[IO])
      a2 <- wrapped(reqFromIp("198.51.100.50")).getOrElse(Response.notFound[IO])
    } yield
      expect(a1.status == Status.Ok, "first request fits in cap")
        .and(expect(a2.status == Status.TooManyRequests, "second exceeds; allowlist does not apply to this IP"))
  }

  test("appliesTo predicate: routes excluded from bandwidth limit pass through unconstrained") {
    val cap = 1L // 1 byte cap — would reject anything ≥ 1 byte
    val window = 1.minute
    val responseSize = 1024L * 1024L // 1 MB
    val onlyHeavy: Request[IO] => Boolean = req => req.uri.path.toString.contains("heavy")
    for {
      mw <- PerIpBandwidthLimitMiddleware[IO](cap, window, appliesTo = onlyHeavy)
      wrapped = mw(fixedSizeRoute(responseSize))
      light <- wrapped(reqFromIp("203.0.113.40", "/light")).getOrElse(Response.notFound[IO])
      heavy <- wrapped(reqFromIp("203.0.113.40", "/heavy")).getOrElse(Response.notFound[IO])
    } yield
      expect(light.status == Status.Ok, "light route bypasses bandwidth limit").and(
        expect(heavy.status == Status.TooManyRequests, "heavy route is bandwidth-limited")
      )
  }
}
