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
  *   - Like `PerIpRateLimitMiddleware`, the per-IP map is keyed by client IP and not actively GC'd. Stale entries are pruned on heavyweight
  *     snapshot requests. Bounded under the small-distinct-callers assumption.
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

  private final case class BandwidthState(
    perIp: Map[String, List[(Long, Long)]],
    aggregateTimestampedBytesDesc: List[(Long, Long)],
    adaptive: Map[String, AdaptiveClientState]
  )

  private final case class AdaptiveClientState(
    timestampedBytesDesc: List[(Long, Long)],
    penaltyLevel: Int,
    lastPenaltyMs: Long
  )

  private final case class Rejection(scope: String, observed: Long, cap: Long, retryAfterSeconds: Long)

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
    *   client IPs that bypass the per-IP bandwidth check. Used for trusted infra (snapshot streaming, monitoring, peer-to-peer recovery)
    *   that legitimately exceeds the per-IP byte cap. The aggregate node-wide budget, when configured, still applies. Match is exact-string
    *   against the resolved IP (X-Forwarded-For first hop or remote address). Mirrors [[PerIpRateLimitMiddleware]]'s allowlist so a single
    *   `CL_SNAPSHOT_PER_IP_ALLOWLIST` env value bypasses both per-IP limiters in lockstep.
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
    maxBytesPerAggregateLongWindow: Long = 0L,
    aggregateLongWindowDuration: FiniteDuration = 0.seconds,
    retryAfterSeconds: Long = 5,
    adaptiveBackoffEnabled: Boolean = false,
    adaptiveBackoffMaxRequestsPerWindow: Int = 0,
    adaptiveBackoffMaxBytesPerWindow: Long = 0L,
    adaptiveBackoffWindowDuration: FiniteDuration = 0.seconds,
    adaptiveBackoffBaseRetryAfterSeconds: Long = 3,
    adaptiveBackoffMaxRetryAfterSeconds: Long = 300,
    adaptiveBackoffPenaltyDecay: FiniteDuration = 5.minutes,
    adaptiveBackoffApplyToAllowlist: Boolean = false,
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
    val aggregateWindowMillis = aggregateLongWindowDuration.toMillis
    val aggregateEnabled = maxBytesPerAggregateLongWindow > 0L && aggregateWindowMillis > 0L
    val adaptiveWindowMillis = adaptiveBackoffWindowDuration.toMillis
    val adaptivePenaltyDecayMillis = math.max(1L, adaptiveBackoffPenaltyDecay.toMillis)
    val adaptiveEnabled =
      adaptiveBackoffEnabled &&
        adaptiveWindowMillis > 0L &&
        (adaptiveBackoffMaxRequestsPerWindow > 0 || adaptiveBackoffMaxBytesPerWindow > 0L)
    val retentionWindowMillis =
      List(
        Some(windowMillis),
        Option.when(longWindowEnabled)(longWindowMillis),
        Option.when(aggregateEnabled)(aggregateWindowMillis),
        Option.when(adaptiveEnabled)(adaptiveWindowMillis)
      ).flatten.max
    val effectiveEstimator: Request[F] => F[Option[Long]] =
      routeSizeEstimator.getOrElse(noRouteSizeEstimator[F] _)

    def trimSamples(samples: List[(Long, Long)], nowMs: Long): List[(Long, Long)] =
      samples.takeWhile(_._1 >= nowMs - retentionWindowMillis)

    def exceededPerIpBudget(samples: List[(Long, Long)], nowMs: Long, nextBytes: Long): Option[(Long, Long)] = {
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

    def exceededAggregateBudget(samples: List[(Long, Long)], nowMs: Long, nextBytes: Long): Option[(Long, Long)] =
      if (aggregateEnabled) {
        val aggregateSum = samples.iterator.collect { case (ts, bytes) if ts >= nowMs - aggregateWindowMillis => bytes }.sum
        val aggregateObserved = aggregateSum + nextBytes
        if (aggregateObserved > maxBytesPerAggregateLongWindow || (nextBytes === 0L && aggregateSum >= maxBytesPerAggregateLongWindow))
          Some((aggregateObserved, maxBytesPerAggregateLongWindow))
        else
          None
      } else
        None

    def decayedPenaltyLevel(state: AdaptiveClientState, nowMs: Long): Int = {
      val decaySteps = ((nowMs - state.lastPenaltyMs).max(0L) / adaptivePenaltyDecayMillis).toInt
      (state.penaltyLevel - decaySteps).max(0)
    }

    def adaptiveRetryAfterSeconds(level: Int): Long = {
      val boundedLevel = level.max(0).min(20)
      val multiplier = 1L << (boundedLevel - 1).max(0).min(10)
      (adaptiveBackoffBaseRetryAfterSeconds.max(1L) * multiplier).min(adaptiveBackoffMaxRetryAfterSeconds.max(1L))
    }

    def exceededAdaptiveBudget(
      state: BandwidthState,
      ipOpt: Option[String],
      nowMs: Long,
      nextBytes: Long
    ): Option[(String, Long, Long)] =
      if (adaptiveEnabled)
        ipOpt.flatMap { ip =>
          val clientState = state.adaptive.getOrElse(ip, AdaptiveClientState(Nil, 0, nowMs))
          val samples = clientState.timestampedBytesDesc.takeWhile(_._1 >= nowMs - adaptiveWindowMillis)
          val requestCount = samples.size.toLong
          val byteSum = samples.iterator.map(_._2).sum
          val observedRequests = requestCount + 1L
          val observedBytes = byteSum + nextBytes
          val byRequests =
            Option.when(adaptiveBackoffMaxRequestsPerWindow > 0 && observedRequests > adaptiveBackoffMaxRequestsPerWindow.toLong)(
              ("adaptive_request", observedRequests, adaptiveBackoffMaxRequestsPerWindow.toLong)
            )
          val byBytes =
            Option.when(adaptiveBackoffMaxBytesPerWindow > 0L && observedBytes > adaptiveBackoffMaxBytesPerWindow)(
              ("adaptive_bandwidth", observedBytes, adaptiveBackoffMaxBytesPerWindow)
            )

          byBytes.orElse(byRequests)
        }
      else
        None

    def exceededBudget(
      state: BandwidthState,
      ipOpt: Option[String],
      adaptiveIpOpt: Option[String],
      nowMs: Long,
      nextBytes: Long
    ): Option[Rejection] = {
      val aggregateKept = trimSamples(state.aggregateTimestampedBytesDesc, nowMs)
      exceededAggregateBudget(aggregateKept, nowMs, nextBytes).map {
        case (observed, cap) => Rejection("aggregate", observed, cap, retryAfterSeconds)
      }.orElse {
        ipOpt.flatMap { ip =>
          val kept = state.perIp.get(ip).map(trimSamples(_, nowMs)).getOrElse(Nil)
          exceededPerIpBudget(kept, nowMs, nextBytes).map {
            case (observed, cap) => Rejection(ip, observed, cap, retryAfterSeconds)
          }
        }
      }.orElse {
        exceededAdaptiveBudget(state, adaptiveIpOpt, nowMs, nextBytes).map {
          case (scope, observed, cap) =>
            val clientState = adaptiveIpOpt.flatMap(state.adaptive.get).getOrElse(AdaptiveClientState(Nil, 0, nowMs))
            val level = decayedPenaltyLevel(clientState, nowMs) + 1
            Rejection(scope, observed, cap, adaptiveRetryAfterSeconds(level))
        }
      }
    }

    def trimState(state: BandwidthState, nowMs: Long): BandwidthState =
      BandwidthState(
        perIp = state.perIp.view.mapValues(trimSamples(_, nowMs)).filter(_._2.nonEmpty).toMap,
        aggregateTimestampedBytesDesc = trimSamples(state.aggregateTimestampedBytesDesc, nowMs),
        adaptive = state.adaptive.view.mapValues { clientState =>
          clientState.copy(
            timestampedBytesDesc = clientState.timestampedBytesDesc.takeWhile(_._1 >= nowMs - adaptiveWindowMillis),
            penaltyLevel = decayedPenaltyLevel(clientState, nowMs)
          )
        }.filter { case (_, clientState) => clientState.timestampedBytesDesc.nonEmpty || clientState.penaltyLevel > 0 }.toMap
      )

    def recordAdaptive(
      state: BandwidthState,
      ipOpt: Option[String],
      nowMs: Long,
      bytes: Long
    ): BandwidthState =
      if (!adaptiveEnabled)
        state
      else
        ipOpt match {
          case Some(ip) =>
            val prior = state.adaptive.getOrElse(ip, AdaptiveClientState(Nil, 0, nowMs))
            val kept = prior.timestampedBytesDesc.takeWhile(_._1 >= nowMs - adaptiveWindowMillis)
            state.copy(adaptive =
              state.adaptive
                .updated(ip, prior.copy(timestampedBytesDesc = (nowMs, bytes) :: kept, penaltyLevel = decayedPenaltyLevel(prior, nowMs)))
            )
          case None => state
        }

    def penalizeAdaptive(
      state: BandwidthState,
      ipOpt: Option[String],
      nowMs: Long
    ): BandwidthState =
      if (!adaptiveEnabled)
        state
      else
        ipOpt match {
          case Some(ip) =>
            val prior = state.adaptive.getOrElse(ip, AdaptiveClientState(Nil, 0, nowMs))
            val kept = prior.timestampedBytesDesc.takeWhile(_._1 >= nowMs - adaptiveWindowMillis)
            val nextLevel = (decayedPenaltyLevel(prior, nowMs) + 1).min(20)
            state.copy(adaptive = state.adaptive.updated(ip, AdaptiveClientState(kept, nextLevel, nowMs)))
          case None => state
        }

    def reserveBytes(
      state: BandwidthState,
      ipOpt: Option[String],
      adaptiveIpOpt: Option[String],
      nowMs: Long,
      bytes: Long
    ): BandwidthState = {
      val trimmed = trimState(state, nowMs)
      val withAggregate =
        if (aggregateEnabled) trimmed.copy(aggregateTimestampedBytesDesc = (nowMs, bytes) :: trimmed.aggregateTimestampedBytesDesc)
        else trimmed
      val withPerIp = ipOpt match {
        case Some(ip) =>
          val prior = withAggregate.perIp.getOrElse(ip, Nil)
          withAggregate.copy(perIp = withAggregate.perIp.updated(ip, (nowMs, bytes) :: prior))
        case None => withAggregate
      }
      recordAdaptive(withPerIp, adaptiveIpOpt, nowMs, bytes)
    }

    def runInnerWithPostCheck(
      routes: HttpRoutes[F],
      req: Request[F],
      ipOpt: Option[String],
      adaptiveIpOpt: Option[String],
      stateRef: Ref[F, BandwidthState],
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
            .modify[Option[(String, Long, Long, Long)]] { state =>
              val trimmed = trimState(state, nowMs2)
              exceededBudget(trimmed, ipOpt, adaptiveIpOpt, nowMs2, nextBytes = responseBytes) match {
                case Some(rejected) =>
                  val next = if (rejected.scope.startsWith("adaptive_")) penalizeAdaptive(trimmed, adaptiveIpOpt, nowMs2) else trimmed
                  (next, Some((rejected.scope, rejected.observed, rejected.cap, rejected.retryAfterSeconds)))
                case None => (reserveBytes(trimmed, ipOpt, adaptiveIpOpt, nowMs2, responseBytes), None)
              }
            }
            .flatMap {
              case None                                     => resp.pure[F]
              case Some((scope, observed, cap, retryAfter)) =>
                // Drain the unused inner body so any held resources release. Then return 429.
                resp.body.compile.drain.attempt.void >>
                  onReject.traverse_(_(req, scope, observed, cap)) >>
                  rejectFast(scope, cap, observed, retryAfter, logger)
            }
        }
      }

    Ref.of[F, BandwidthState](BandwidthState(Map.empty, Nil, Map.empty)).map { stateRef => routes: HttpRoutes[F] =>
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
          val perIpBudgetIpOpt: Option[String] = clientIpOpt.filterNot(allowlist.contains)
          val adaptiveIpOpt: Option[String] =
            if (adaptiveBackoffApplyToAllowlist) clientIpOpt
            else clientIpOpt.filterNot(allowlist.contains)

          clientIpOpt match {
            case _ =>
              // Cheap pre-check: if this IP is ALREADY at the cap (sum of in-window kept bytes
              // already >= cap), reject without invoking the inner route. This avoids the heap-
              // allocation cost for every excess request from a freeloader once they're throttled.
              OptionT.liftF(Async[F].realTime).flatMap { now =>
                val nowMs = now.toMillis
                OptionT.liftF(stateRef.get).flatMap { snap =>
                  val trimmed = trimState(snap, nowMs)
                  exceededBudget(trimmed, perIpBudgetIpOpt, adaptiveIpOpt, nowMs, nextBytes = 0L) match {
                    case Some(rejected) =>
                      OptionT.liftF(
                        stateRef.update(state =>
                          if (rejected.scope.startsWith("adaptive_")) penalizeAdaptive(trimState(state, nowMs), adaptiveIpOpt, nowMs)
                          else trimState(state, nowMs)
                        ) >>
                          onReject.traverse_(_(req, rejected.scope, rejected.observed, rejected.cap)) >>
                          rejectFast(rejected.scope, rejected.cap, rejected.observed, rejected.retryAfterSeconds, logger)
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
                          exceededBudget(trimmed, perIpBudgetIpOpt, adaptiveIpOpt, nowMs, nextBytes = estimated) match {
                            case Some(rejected) =>
                              OptionT.liftF(
                                stateRef.update(state =>
                                  if (rejected.scope.startsWith("adaptive_"))
                                    penalizeAdaptive(trimState(state, nowMs), adaptiveIpOpt, nowMs)
                                  else trimState(state, nowMs)
                                ) >>
                                  onReject.traverse_(_(req, rejected.scope, rejected.observed, rejected.cap)) >>
                                  rejectFast(rejected.scope, rejected.cap, rejected.observed, rejected.retryAfterSeconds, logger)
                              )
                            case None =>
                              runInnerWithPostCheck(
                                routes,
                                req,
                                perIpBudgetIpOpt,
                                adaptiveIpOpt,
                                stateRef,
                                logger,
                                onReject
                              )
                          }
                        case _ =>
                          runInnerWithPostCheck(routes, req, perIpBudgetIpOpt, adaptiveIpOpt, stateRef, logger, onReject)
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
