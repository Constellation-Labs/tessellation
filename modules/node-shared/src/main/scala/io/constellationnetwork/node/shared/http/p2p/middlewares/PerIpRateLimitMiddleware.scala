package io.constellationnetwork.node.shared.http.p2p.middlewares

import cats.data.{Kleisli, OptionT}
import cats.effect.kernel.{Async, Ref}
import cats.syntax.all._

import scala.concurrent.duration._

import org.http4s._
import org.http4s.headers.{`Retry-After`, `X-Forwarded-For`}
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
    * @param allowlist
    *   client IPs that bypass the counter entirely. Used for trusted infra (snapshot streaming, monitoring, peer-to-peer recovery) that
    *   legitimately exceeds the per-IP cap. Match is exact-string against the resolved IP (X-Forwarded-For first hop or remote address).
    * @param selfExternalIp
    *   the local node's external IP (typically `cfg.http.externalIp`). When provided, the middleware detects the XFF-self-injection case —
    * i.e. the load-balancer or upstream proxy injected the LOCAL node's own IP into `X-Forwarded-For` — and falls back to the TCP remote
    * address instead. Without this guard, all LB-injected requests share a single counter under our own IP, which on bootstrap-source nodes
    * (RunRollback) saturates within seconds and starts 429ing healthcheck probes — an external supervisor then treats the probe failures as
    * liveness failure and SIGTERMs the JVM, producing a 5-7 minute restart loop. Observed on alpha.51 testnet `.193`.
    * @return
    *   a function that wraps `HttpRoutes[F]` with the rate limiter.
    */
  def apply[F[_]: Async](
    maxRequestsPerWindow: Int,
    windowDuration: FiniteDuration,
    retryAfterSeconds: Long = 5,
    allowlist: Set[String] = Set.empty,
    selfExternalIp: Option[String] = None,
    onReject: Option[(Request[F], String, Int, Int) => F[Unit]] = None
  ): F[HttpRoutes[F] => HttpRoutes[F]] = {
    val logger = Slf4jLogger.getLogger[F]
    val windowMillis = windowDuration.toMillis

    Ref.of[F, Map[String, IpState]](Map.empty).map { stateRef => routes: HttpRoutes[F] =>
      Kleisli { req =>
        // Resolve the client IP. The .flatMap(_.values.head) correctly unwraps the Option[Node]
        // before .toString — calling .toString on the Option directly produces "Some(<ip>)" keys.
        // Self-injection guard: when XFF first-hop matches our own external IP, treat the header as
        // LB injection rather than a true upstream client identity, and fall back to the connection
        // remote address. Prevents the .193 self-loop SIGTERM cascade.
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
          case None =>
            // No identifiable source; pass through. Avoids blocking loopback or misconfigured probes.
            routes(req)

          case Some(ip) if allowlist.contains(ip) =>
            // Trusted infra IP — skip the counter check entirely.
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
                    onReject.traverse_(_(req, ip, maxRequestsPerWindow, maxRequestsPerWindow)) >>
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
