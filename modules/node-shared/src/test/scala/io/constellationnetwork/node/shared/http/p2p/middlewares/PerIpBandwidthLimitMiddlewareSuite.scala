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

  test("selfExternalIp guard: XFF first-hop matching self IP is dropped, falls back to remote (here: None → pass-through)") {
    val selfIp = "52.8.132.193"
    val cap = 100L * 1024L * 1024L
    val window = 1.minute
    val responseSize = 80L * 1024L * 1024L // single response < cap; two would normally exceed
    for {
      mw <- PerIpBandwidthLimitMiddleware[IO](cap, window, selfExternalIp = Some(selfIp))
      wrapped = mw(fixedSizeRoute(responseSize))
      r1 <- wrapped(reqFromIp(selfIp)).getOrElse(Response.notFound[IO])
      r2 <- wrapped(reqFromIp(selfIp)).getOrElse(Response.notFound[IO])
      r3 <- wrapped(reqFromIp(selfIp)).getOrElse(Response.notFound[IO])
    } yield
      expect(r1.status == Status.Ok, "self-XFF r1 passes (guard dropped it, no remote → unkeyed)")
        .and(expect(r2.status == Status.Ok, "self-XFF r2 passes (would be 429 without guard)"))
        .and(expect(r3.status == Status.Ok, "self-XFF r3 passes (would be 429 without guard)"))
  }

  test("selfExternalIp guard: non-self XFF is still bandwidth-capped") {
    val selfIp = "52.8.132.193"
    val cap = 100L * 1024L * 1024L
    val window = 1.minute
    val responseSize = 80L * 1024L * 1024L
    for {
      mw <- PerIpBandwidthLimitMiddleware[IO](cap, window, selfExternalIp = Some(selfIp))
      wrapped = mw(fixedSizeRoute(responseSize))
      r1 <- wrapped(reqFromIp("198.51.100.77")).getOrElse(Response.notFound[IO])
      r2 <- wrapped(reqFromIp("198.51.100.77")).getOrElse(Response.notFound[IO])
    } yield
      expect(r1.status == Status.Ok, "external r1 accepted")
        .and(expect(r2.status == Status.TooManyRequests, "external r2 rejected — guard didn't disable normal limiting"))
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

  // ----- routeSizeEstimator: pre-flight reject -----

  // A route that records how many times its handler ran. The estimator-rejected case
  // must show this counter still at zero -- proving the inner route was never invoked
  // (the whole point of the pre-flight path: avoid the 100 MB response construction).
  private def countingRoute(bytes: Long, counter: cats.effect.Ref[IO, Int]): HttpRoutes[IO] =
    Kleisli(_ =>
      OptionT.liftF(
        counter.update(_ + 1) >>
          IO.pure(Response[IO](status = Status.Ok, headers = Headers(`Content-Length`.unsafeFromLong(bytes))))
      )
    )

  test("routeSizeEstimator: over-cap estimate rejects with 429 WITHOUT invoking the inner route") {
    val cap = 100L * 1024L * 1024L // 100 MB
    val window = 1.minute
    val estimatedBytes = 200L * 1024L * 1024L // 200 MB -- intentionally larger than cap
    for {
      counter <- cats.effect.Ref[IO].of(0)
      estimator = (_: Request[IO]) => IO.pure(Option(estimatedBytes))
      mw <- PerIpBandwidthLimitMiddleware[IO](cap, window, routeSizeEstimator = Some(estimator))
      wrapped = mw(countingRoute(estimatedBytes, counter))
      resp <- wrapped(reqFromIp("203.0.113.50")).getOrElse(Response.notFound[IO])
      invocations <- counter.get
    } yield
      expect(resp.status == Status.TooManyRequests, "pre-flight reject returns 429").and(
        expect.eql(0, invocations)
      )
  }

  test("routeSizeEstimator: under-cap estimate runs the inner route and accepts (reserves once)") {
    val cap = 100L * 1024L * 1024L
    val window = 1.minute
    val responseBytes = 10L * 1024L * 1024L // 10 MB
    for {
      counter <- cats.effect.Ref[IO].of(0)
      estimator = (_: Request[IO]) => IO.pure(Option(responseBytes))
      mw <- PerIpBandwidthLimitMiddleware[IO](cap, window, routeSizeEstimator = Some(estimator))
      wrapped = mw(countingRoute(responseBytes, counter))
      r1 <- wrapped(reqFromIp("203.0.113.51")).getOrElse(Response.notFound[IO])
      invocations <- counter.get
    } yield
      expect(r1.status == Status.Ok, "request under cap accepted").and(
        expect.eql(1, invocations)
      )
  }

  test("routeSizeEstimator: None means estimator absent -- legacy Content-Length post-check still applies") {
    val cap = 100L * 1024L * 1024L
    val window = 1.minute
    val responseSize = 60L * 1024L * 1024L // 60 MB; two = 120 MB > cap
    for {
      // No estimator passed: the middleware falls back to the legacy post-response path.
      mw <- PerIpBandwidthLimitMiddleware[IO](cap, window)
      wrapped = mw(fixedSizeRoute(responseSize))
      r1 <- wrapped(reqFromIp("203.0.113.52")).getOrElse(Response.notFound[IO])
      r2 <- wrapped(reqFromIp("203.0.113.52")).getOrElse(Response.notFound[IO])
    } yield
      expect(r1.status == Status.Ok, "first request accepted via post-check")
        .and(expect(r2.status == Status.TooManyRequests, "second exceeds cap via post-check"))
  }

  test("routeSizeEstimator: allowlisted IP bypasses estimator + cap entirely") {
    val streaming = "13.57.169.99"
    val cap = 1L
    val window = 1.minute
    // Estimator would say 200 MB and reject under normal IPs at cap=1, but the
    // allowlist branch fires BEFORE the estimator check.
    val estimator = (_: Request[IO]) => IO.pure(Option(200L * 1024L * 1024L))
    for {
      counter <- cats.effect.Ref[IO].of(0)
      mw <- PerIpBandwidthLimitMiddleware[IO](
        cap,
        window,
        allowlist = Set(streaming),
        routeSizeEstimator = Some(estimator)
      )
      wrapped = mw(countingRoute(80L * 1024L * 1024L, counter))
      r1 <- wrapped(reqFromIp(streaming)).getOrElse(Response.notFound[IO])
      invocations <- counter.get
    } yield
      expect(r1.status == Status.Ok, "allowlisted IP accepted").and(
        expect.eql(1, invocations)
      )
  }
}
