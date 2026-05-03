package io.constellationnetwork.node.shared.http.p2p.middlewares

import cats.data.{Kleisli, OptionT}
import cats.effect.kernel.{Async, Ref}
import cats.syntax.all._

import scala.concurrent.duration._

import org.http4s._
import org.http4s.headers.{`Retry-After`, `X-Forwarded-For`}
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Per-client-IP sliding-window BANDWIDTH limiter.
  *
  * Complements [[PerIpRateLimitMiddleware]] (count-based) and [[ConcurrencyLimitMiddleware]] (parallelism-based) by capping bytes-per-IP
  * over a sliding window. Each catches a different abuse shape:
  *
  *   - rate limit catches high-frequency tight-loop polling
  *   - concurrency limit catches deep-parallel fan-out from a single client
  *   - this one catches sustained bulk-egress from a slow-rate but heavyweight client
  *
  * The 2026-04-29 testnet measurement showed `/global-snapshots/latest/combined/stream` accounting for 98.8% of cluster egress, with
  * individual external clients pulling 72 MB once every 30-90s. None of those clients hit the existing 30 req/min count cap, but their
  * aggregate egress was ~25-40 MiB/s. Bandwidth-based limiting is the right primitive for that pattern.
  *
  * ==Mechanism==
  *
  * Pre-flight reservation. The middleware lets the inner route produce its `Response` (which sets `Content-Length` for the heavyweight
  * snapshot routes that the bandwidth fix at commit `910864f7f` hardened), then atomically:
  *
  *   1. trims per-IP timestamp/byte tuples older than `windowDuration` 2. if `sum(kept) + Content-Length > maxBytesPerWindow` → reject with
  *      429 + Retry-After (and drain the unused inner body) 3. else → record `(now, Content-Length)` and serve the inner response
  *
  * Caveats:
  *
  *   - Rejection happens AFTER the inner route handler ran (which for `combined/stream` reads bytes into memory via the Scaffeine cache).
  *     The middleware avoids EGRESS bytes but not the heap allocation per cache miss. The Scaffeine cache amortizes that cost across
  *     requests for the same ordinal, which is the common case.
  *   - When `Content-Length` is missing (chunked routes that don't pre-compute size), the request passes through with `0` cost accounted.
  *     Use this middleware only on routes that set `Content-Length`.
  *   - Like `PerIpRateLimitMiddleware`, the per-IP map is keyed by client IP and not actively GC'd. Stale entries are pruned on the next
  *     request from the same IP. Bounded under the small-distinct-callers assumption.
  *
  * Composition order recommendation when stacked with the others:
  *
  * {{{
  *   public-route
  *     ──> PerIpRateLimitMiddleware     // cheapest reject (just a counter)
  *     ──> PerIpBandwidthLimitMiddleware // pre-flight read of inner response Content-Length
  *     ──> ConcurrencyLimitMiddleware   // semaphore acquire
  *     ──> handler
  * }}}
  *
  * The inner-most placement makes the outer cheap rejects bypass any Content-Length read from this middleware. The outer-most placement
  * (rate limit) means a 429-rejected request never even checks bandwidth state.
  */
object PerIpBandwidthLimitMiddleware {

  private final case class IpState(timestampedBytesDesc: List[(Long, Long)])

  /** Build the middleware.
    *
    * @param maxBytesPerWindow
    *   total bytes a single IP is permitted within the window. Excess responses are converted to 429.
    * @param windowDuration
    *   sliding-window length. Older samples are evicted on each access.
    * @param retryAfterSeconds
    *   value for the `Retry-After` header on 429 responses.
    * @param appliesTo
    *   predicate selecting which requests this middleware enforces on. Requests for which this returns `false` are passed through without
    *   any bandwidth accounting. Defaults to "all requests."
    * @param allowlist
    *   client IPs that bypass the bandwidth check entirely. Used for trusted infra (snapshot streaming, monitoring, peer-to-peer recovery)
    *   that legitimately exceeds the per-IP byte cap. Match is exact-string against the resolved IP (X-Forwarded-For first hop or remote
    *   address). Mirrors [[PerIpRateLimitMiddleware]]'s allowlist so a single `CL_SNAPSHOT_PER_IP_ALLOWLIST` env value bypasses both
    *   limiters in lockstep.
    * @param selfExternalIp
    *   the local node's external IP. When provided, the middleware detects the XFF-self-injection case (LB injected our own IP into
    *   `X-Forwarded-For`) and falls back to the TCP remote address. Mirrors [[PerIpRateLimitMiddleware]]'s guard; see that header doc for
    *   the full rationale (.193 self-loop SIGTERM cascade observed 2026-05-02).
    */
  def apply[F[_]: Async](
    maxBytesPerWindow: Long,
    windowDuration: FiniteDuration,
    retryAfterSeconds: Long = 5,
    appliesTo: Request[F] => Boolean = (_: Request[F]) => true,
    allowlist: Set[String] = Set.empty,
    selfExternalIp: Option[String] = None
  ): F[HttpRoutes[F] => HttpRoutes[F]] = {
    val logger = Slf4jLogger.getLogger[F]
    val windowMillis = windowDuration.toMillis

    Ref.of[F, Map[String, IpState]](Map.empty).map { stateRef => routes: HttpRoutes[F] =>
      Kleisli { req =>
        if (!appliesTo(req)) routes(req)
        else {
          // Match PerIpRateLimitMiddleware's IP extraction: first hop of X-Forwarded-For
          // takes priority, fall back to TCP remote. Requests with no identifiable source pass
          // through unconditionally. The .flatMap(_.values.head) correctly unwraps the Option[Node]
          // before .toString — calling .toString on the Option directly produces "Some(<ip>)" keys.
          // Self-injection guard mirrors PerIpRateLimitMiddleware: if XFF first-hop matches our
          // own external IP, treat as LB injection and fall back to the TCP remote.
          val xffFirstHop: Option[String] =
            req.headers
              .get[`X-Forwarded-For`]
              .flatMap(_.values.head)
              .map(_.toString.split(",").head.trim)
              .filter(_.nonEmpty)
          val xffIsSelfInjection: Boolean =
            (selfExternalIp, xffFirstHop).mapN(_ == _).getOrElse(false)
          val clientIpOpt: Option[String] =
            (if (xffIsSelfInjection) None else xffFirstHop)
              .orElse(req.remote.map(_.host.toString))

          clientIpOpt match {
            case None                               => routes(req)
            case Some(ip) if allowlist.contains(ip) => routes(req) // trusted infra bypasses bandwidth
            case Some(ip)                           =>
              // Cheap pre-check: if this IP is ALREADY at the cap (sum of in-window kept bytes
              // already >= cap), reject without invoking the inner route. This avoids the heap-
              // allocation cost for every excess request from a freeloader once they're throttled.
              OptionT.liftF(Async[F].realTime).flatMap { now =>
                val nowMs = now.toMillis
                val cutoff = nowMs - windowMillis
                OptionT.liftF(stateRef.get).flatMap { snap =>
                  val kept = snap
                    .get(ip)
                    .map(_.timestampedBytesDesc.takeWhile(_._1 >= cutoff))
                    .getOrElse(Nil)
                  val sumKept = kept.iterator.map(_._2).sum
                  if (sumKept >= maxBytesPerWindow) {
                    OptionT.liftF(rejectFast(ip, maxBytesPerWindow, sumKept, retryAfterSeconds, logger))
                  } else {
                    // Run inner route, then check Content-Length and reserve atomically.
                    routes(req).semiflatMap { resp =>
                      val responseBytes = resp.contentLength.getOrElse(0L)
                      Async[F].realTime.flatMap { now2 =>
                        val nowMs2 = now2.toMillis
                        val cutoff2 = nowMs2 - windowMillis
                        // Return type encodes the decision: None = accepted, Some(observed) = rejected.
                        // Keeps the `sumNow` value out of the modify closure so the post-modify
                        // branches can report the observed total in the rejection log.
                        stateRef
                          .modify[Option[Long]] { m =>
                            val prev = m.getOrElse(ip, IpState(Nil))
                            val keptNow = prev.timestampedBytesDesc.takeWhile(_._1 >= cutoff2)
                            val sumNow = keptNow.iterator.map(_._2).sum
                            if (sumNow + responseBytes > maxBytesPerWindow) {
                              // Don't record — we're rejecting. Trimmed list is preserved.
                              (m.updated(ip, IpState(keptNow)), Some(sumNow + responseBytes))
                            } else {
                              (m.updated(ip, IpState((nowMs2, responseBytes) :: keptNow)), None)
                            }
                          }
                          .flatMap {
                            case None           => Async[F].pure(resp)
                            case Some(observed) =>
                              // Drain the unused inner body so any held resources release. Then return 429.
                              resp.body.compile.drain.attempt.void >>
                                rejectFast(ip, maxBytesPerWindow, observed, retryAfterSeconds, logger)
                          }
                      }
                    }
                  }
                }
              }
          }
        }
      }
    }
  }

  private def rejectFast[F[_]: Async](
    ip: String,
    cap: Long,
    observed: Long,
    retryAfterSeconds: Long,
    logger: org.typelevel.log4cats.SelfAwareStructuredLogger[F]
  ): F[Response[F]] =
    logger.debug(s"Bandwidth limit exceeded for IP $ip (observed=$observed cap=$cap)") >>
      Response[F](Status.TooManyRequests)
        .putHeaders(`Retry-After`.unsafeFromLong(retryAfterSeconds))
        .pure[F]
}
