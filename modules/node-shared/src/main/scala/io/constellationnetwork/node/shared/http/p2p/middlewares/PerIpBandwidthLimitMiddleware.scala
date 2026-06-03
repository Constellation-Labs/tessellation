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
  * A testnet measurement showed `/global-snapshots/latest/combined/stream` accounting for 98.8% of cluster egress, with individual external
  * clients pulling 72 MB once every 30-90s. None of those clients hit the existing 30 req/min count cap, but their aggregate egress was
  * ~25-40 MiB/s. Bandwidth-based limiting is the right primitive for that pattern.
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

  /** Default route size estimator that never predicts a size. Wired in when the caller has not registered a route-specific estimator; the
    * middleware then falls through to its legacy post-response Content-Length accounting. Top-level rather than an inline default because
    * Scala-2 default values cannot see the caller's implicit scope (the `Async[F]` constraint), so we cannot reference `Async[F].pure(...)`
    * directly in the `apply` parameter default.
    */
  def noRouteSizeEstimator[F[_]: Async](req: Request[F]): F[Option[Long]] = {
    val _ = req
    Async[F].pure(Option.empty[Long])
  }

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
    *   the full rationale (.193 self-loop SIGTERM cascade observed on testnet).
    * @param routeSizeEstimator
    *   per-request pre-flight size estimator. `Some(estimator)` enables the pre-flight reject path: when the estimator returns
    *   `Some(bytes)` and `sumKept + bytes > maxBytesPerWindow`, the middleware returns 429 BEFORE invoking the inner route. Estimator
    *   returning `None` falls through to the legacy post-response `Content-Length` accounting (covers routes the caller has not registered
    *   an estimator for). The intent is to avoid constructing a 100 MB response just to drain it on an over-cap IP. The post-response check
    *   is still applied for defense in depth in case the estimator under-reports. `None` preserves the legacy behavior end-to-end.
    */
  def apply[F[_]: Async](
    maxBytesPerWindow: Long,
    windowDuration: FiniteDuration,
    maxBytesPerLongWindow: Long = 0L,
    longWindowDuration: FiniteDuration = 0.seconds,
    retryAfterSeconds: Long = 5,
    appliesTo: Request[F] => Boolean = (_: Request[F]) => true,
    allowlist: Set[String] = Set.empty,
    selfExternalIp: Option[String] = None,
    routeSizeEstimator: Option[Request[F] => F[Option[Long]]] = None,
    onReject: Option[(Request[F], String, Long, Long) => F[Unit]] = None
  ): F[HttpRoutes[F] => HttpRoutes[F]] = {
    val logger = Slf4jLogger.getLogger[F]
    val windowMillis = windowDuration.toMillis
    val longWindowMillis = longWindowDuration.toMillis
    val longWindowEnabled = maxBytesPerLongWindow > 0L && longWindowMillis > 0L
    val retentionWindowMillis = if (longWindowEnabled) math.max(windowMillis, longWindowMillis) else windowMillis
    val effectiveEstimator: Request[F] => F[Option[Long]] =
      routeSizeEstimator.getOrElse(noRouteSizeEstimator[F] _)

    def trimSamples(samples: List[(Long, Long)], nowMs: Long): List[(Long, Long)] =
      samples.takeWhile(_._1 >= nowMs - retentionWindowMillis)

    def exceededBudget(samples: List[(Long, Long)], nowMs: Long, nextBytes: Long): Option[(Long, Long)] = {
      val shortSum = samples.iterator.collect { case (ts, bytes) if ts >= nowMs - windowMillis => bytes }.sum
      val shortObserved = shortSum + nextBytes
      if (shortObserved > maxBytesPerWindow || (nextBytes === 0L && shortSum >= maxBytesPerWindow))
        Some((shortObserved, maxBytesPerWindow))
      else if (longWindowEnabled) {
        val longSum = samples.iterator.collect { case (ts, bytes) if ts >= nowMs - longWindowMillis => bytes }.sum
        val longObserved = longSum + nextBytes
        if (longObserved > maxBytesPerLongWindow || (nextBytes === 0L && longSum >= maxBytesPerLongWindow))
          Some((longObserved, maxBytesPerLongWindow))
        else
          None
      } else
        None
    }

    def runInnerWithPostCheck(
      routes: HttpRoutes[F],
      req: Request[F],
      ip: String,
      stateRef: Ref[F, Map[String, IpState]],
      retryAfterSeconds: Long,
      logger: org.typelevel.log4cats.SelfAwareStructuredLogger[F],
      onReject: Option[(Request[F], String, Long, Long) => F[Unit]]
    ): OptionT[F, Response[F]] =
      // Run inner route, then check Content-Length and reserve atomically.
      // This second check stays even when the estimator accepted, so an
      // under-reporting estimator can't silently bypass the cap (defense in depth).
      routes(req).semiflatMap { resp =>
        val responseBytes = resp.contentLength.getOrElse(0L)
        Async[F].realTime.flatMap { now2 =>
          val nowMs2 = now2.toMillis
          // Return type encodes the decision: None = accepted, Some(observed, cap) = rejected.
          // Keeps the observed value out of the modify closure so the post-modify branches can
          // report the right window in the rejection log.
          stateRef
            .modify[Option[(Long, Long)]] { m =>
              val prev = m.getOrElse(ip, IpState(Nil))
              val keptNow = trimSamples(prev.timestampedBytesDesc, nowMs2)
              exceededBudget(keptNow, nowMs2, nextBytes = responseBytes) match {
                case Some(rejected) =>
                  // Don't record - we're rejecting. Trimmed list is preserved.
                  (m.updated(ip, IpState(keptNow)), Some(rejected))
                case None =>
                  (m.updated(ip, IpState((nowMs2, responseBytes) :: keptNow)), None)
              }
            }
            .flatMap {
              case None                  => resp.pure[F]
              case Some((observed, cap)) =>
                // Drain the unused inner body so any held resources release. Then return 429.
                resp.body.compile.drain.attempt.void >>
                  onReject.traverse_(_(req, ip, observed, cap)) >>
                  rejectFast(ip, cap, observed, retryAfterSeconds, logger)
            }
        }
      }

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
            selfExternalIp.exists(self => xffFirstHop.contains(self))
          val clientIpOpt: Option[String] =
            xffFirstHop
              .filterNot(_ => xffIsSelfInjection)
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
                OptionT.liftF(stateRef.get).flatMap { snap =>
                  val kept = snap.get(ip).map(s => trimSamples(s.timestampedBytesDesc, nowMs)).getOrElse(Nil)
                  exceededBudget(kept, nowMs, nextBytes = 0L) match {
                    case Some((observed, cap)) =>
                      OptionT.liftF(
                        onReject.traverse_(_(req, ip, observed, cap)) >>
                          rejectFast(ip, cap, observed, retryAfterSeconds, logger)
                      )
                    case None =>
                      // Pre-flight estimator check: if a route-specific estimator can predict the
                      // response size, reject before the route executes. The key win is avoiding
                      // the ~100 MB response-body construction (and resource acquire/drain) for the
                      // combined-stream routes when the IP would be over the cap anyway. Estimators
                      // returning None fall through to the post-response Content-Length path so
                      // routes without estimators preserve legacy behavior.
                      OptionT.liftF(effectiveEstimator(req)).flatMap {
                        case Some(estimated) =>
                          exceededBudget(kept, nowMs, nextBytes = estimated) match {
                            case Some((observed, cap)) =>
                              OptionT.liftF(
                                onReject.traverse_(_(req, ip, observed, cap)) >>
                                  rejectFast(ip, cap, observed, retryAfterSeconds, logger)
                              )
                            case None =>
                              runInnerWithPostCheck(routes, req, ip, stateRef, retryAfterSeconds, logger, onReject)
                          }
                        case _ =>
                          runInnerWithPostCheck(routes, req, ip, stateRef, retryAfterSeconds, logger, onReject)
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
