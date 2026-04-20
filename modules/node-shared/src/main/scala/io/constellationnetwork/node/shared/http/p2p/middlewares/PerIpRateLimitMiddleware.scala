package io.constellationnetwork.node.shared.http.p2p.middlewares

import cats.data.{Kleisli, OptionT}
import cats.effect.kernel.{Async, Ref}
import cats.syntax.all._

import scala.concurrent.duration._

import org.http4s.headers.{`Retry-After`, `X-Forwarded-For`}
import org.http4s.{HttpRoutes, Response, Status}
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Per-client-IP sliding-window rate limiter for HTTP routes.
  *
  * Keyed by client IP. For each request, any recorded timestamps older than `windowDuration` are evicted for that IP, then if the remaining
  * count is at or above `maxRequestsPerWindow` the request is rejected with 429 Too Many Requests + Retry-After. Otherwise the current
  * timestamp is recorded and the request is forwarded to the wrapped route.
  *
  * The client IP is taken from the first hop of `X-Forwarded-For` if present (so CloudFront / proxy routing is keyed by the originating
  * peer, not the edge IP) and falls back to the TCP connection's remote address. Requests with no identifiable IP pass through
  * unconditionally.
  *
  * Memory behaviour: the internal map keeps one entry per active IP. Entries for IPs that stop requesting are not actively GC'd — their
  * timestamp list is truncated to the empty set on the next request from the same IP, but the map key remains. In practice the number of
  * distinct caller IPs is small (< 1000 for a testnet), so the leak is bounded. If this assumption ever stops holding, add a periodic
  * cleanup fiber.
  *
  * Complement to [[ConcurrencyLimitMiddleware]]: that one bounds simultaneous dispatch ("how many are being processed right now"), this one
  * bounds rate-over-time per caller ("how many has this caller made in the last minute"). A single slow caller polling in a tight loop
  * saturates neither concurrency (serial requests) nor bandwidth on any single request — but can still consume disproportionate total CPU /
  * egress. This middleware is the guard for that case.
  */
object PerIpRateLimitMiddleware {

  /** Opaque state per IP — only the timestamp list is needed for a sliding window. */
  private final case class IpState(timestampsDesc: List[Long])

  /** Build the middleware.
    *
    * @param maxRequestsPerWindow
    *   max inbound requests per IP within the window. Requests at or above this count are rejected with 429.
    * @param windowDuration
    *   the sliding-window length.
    * @param retryAfterSeconds
    *   value for the Retry-After header on 429 responses.
    * @return
    *   a function that wraps `HttpRoutes[F]` with the rate limiter.
    */
  def apply[F[_]: Async](
    maxRequestsPerWindow: Int,
    windowDuration: FiniteDuration,
    retryAfterSeconds: Long = 5
  ): F[HttpRoutes[F] => HttpRoutes[F]] = {
    val logger = Slf4jLogger.getLogger[F]
    val windowMillis = windowDuration.toMillis

    Ref.of[F, Map[String, IpState]](Map.empty).map { stateRef => routes: HttpRoutes[F] =>
      Kleisli { req =>
        val clientIpOpt: Option[String] =
          req.headers
            .get[`X-Forwarded-For`]
            .map(_.values.head.toString.split(",").head.trim)
            .filter(_.nonEmpty)
            .orElse(req.remote.map(_.host.toString))

        clientIpOpt match {
          case None =>
            // No identifiable source; pass through. Avoids blocking loopback or misconfigured probes.
            routes(req)

          case Some(ip) =>
            OptionT
              .liftF(
                Async[F].realTime.flatMap { now =>
                  val nowMs = now.toMillis
                  val cutoff = nowMs - windowMillis
                  stateRef.modify { m =>
                    val prev = m.getOrElse(ip, IpState(Nil))
                    val kept = prev.timestampsDesc.takeWhile(_ >= cutoff)
                    if (kept.size >= maxRequestsPerWindow) {
                      // Keep the trimmed list; do NOT add this timestamp — we're rejecting.
                      (m.updated(ip, IpState(kept)), false)
                    } else {
                      (m.updated(ip, IpState(nowMs :: kept)), true)
                    }
                  }
                }
              )
              .flatMap {
                case true =>
                  routes(req)
                case false =>
                  OptionT.liftF(
                    logger.debug(s"Rate limit exceeded for IP $ip (max=$maxRequestsPerWindow per ${windowDuration.toSeconds}s)") >>
                      Response[F](Status.TooManyRequests)
                        .putHeaders(`Retry-After`.unsafeFromLong(retryAfterSeconds))
                        .pure[F]
                  )
              }
        }
      }
    }
  }
}
