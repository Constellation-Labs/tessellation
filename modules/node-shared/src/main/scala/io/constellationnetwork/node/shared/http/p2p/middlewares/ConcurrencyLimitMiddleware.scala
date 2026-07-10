package io.constellationnetwork.node.shared.http.p2p.middlewares

import cats.data.{Kleisli, OptionT}
import cats.effect.kernel.{Async, MonadCancel}
import cats.effect.std.Semaphore
import cats.syntax.all._

import org.http4s.headers.`Retry-After`
import org.http4s.{HttpRoutes, Response, Status}

/** Limits concurrent request dispatch for wrapped routes.
  *
  * When the semaphore is saturated, returns 503 Service Unavailable with a Retry-After header instead of queuing requests. This provides
  * backpressure to clients without blocking fibers or starving other route handlers.
  *
  * Designed for snapshot-serving routes where many external clients may simultaneously request historical data. Without this guard, a burst
  * of snapshot fetches (e.g. from 200+ community nodes doing initial sync) can saturate available fibers and delay latency-sensitive
  * consensus gossip.
  *
  * '''Important:''' the semaphore is released when the route handler produces a `Response[F]` header, not when the response body stream is
  * fully consumed by the client. This means `maxConcurrent` bounds concurrent dispatch (including any eager reads like
  * `readBytesWithCache`), not body transfer. The storage-level `concurrentStreams.permit` in `CombinedSnapshotCheckpointFileSystemStorage`
  * provides an additional file-read concurrency bound for the non-cached fallback path.
  */
object ConcurrencyLimitMiddleware {

  /** @param maxConcurrent
    *   Maximum number of requests processed simultaneously. Excess requests get 503.
    * @param retryAfterSeconds
    *   Value for the Retry-After header on 503 responses.
    */
  def apply[F[_]: Async](
    maxConcurrent: Int,
    retryAfterSeconds: Long = 2
  ): F[HttpRoutes[F] => HttpRoutes[F]] =
    Semaphore[F](maxConcurrent.toLong).map { sem => routes: HttpRoutes[F] =>
      Kleisli { req =>
        OptionT.liftF(sem.tryAcquire).flatMap {
          case true =>
            OptionT(
              MonadCancel[F].guarantee(routes(req).value, sem.release)
            )
          case false =>
            OptionT.liftF(
              Response[F](Status.ServiceUnavailable)
                .putHeaders(`Retry-After`.unsafeFromLong(retryAfterSeconds))
                .pure[F]
            )
        }
      }
    }
}
