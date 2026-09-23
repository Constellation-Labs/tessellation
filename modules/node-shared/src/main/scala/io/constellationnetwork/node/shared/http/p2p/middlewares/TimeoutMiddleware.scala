package io.constellationnetwork.node.shared.http.p2p.middlewares

import java.util.concurrent.TimeoutException

import cats.effect.implicits.genTemporalOps
import cats.effect.{Async, Clock}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import fs2.{Pull, Stream}
import org.http4s.client.Client

object TimeoutMiddleware {
  final case class ResponseBodyIdleTimeout(timeout: FiniteDuration)
      extends TimeoutException(s"Timed out waiting $timeout for response body data")
  final case class ResponseBodyLifetimeTimeout(timeout: FiniteDuration)
      extends TimeoutException(s"Response body exceeded its $timeout lifetime")

  /** Enforce both an idle-between-chunks bound and a total response-body lifetime. The lifetime intentionally includes downstream
    * backpressure between pulls: a caller cannot retain an acquired response indefinitely by processing each decoded value slowly.
    */
  private def timeoutResponseBody[F[_]: Async, A](
    stream: Stream[F, A],
    idleTimeout: FiniteDuration,
    lifetime: FiniteDuration
  ): Stream[F, A] =
    Stream.eval(Clock[F].monotonic).flatMap { startedAt =>
      def go(pull: Pull.Timed[F, A]): Pull[F, A, Unit] =
        Pull.eval(Clock[F].monotonic).flatMap { now =>
          val remaining = lifetime - (now - startedAt)

          if (remaining <= 0.nanos)
            Pull.raiseError(ResponseBodyLifetimeTimeout(lifetime))
          else {
            val nextTimeout = idleTimeout.min(remaining)
            pull.timeout(nextTimeout) >> pull.uncons.flatMap {
              case Some((Right(chunk), next)) => Pull.output(chunk) >> go(next)
              case Some((Left(_), _)) =>
                Pull.eval(Clock[F].monotonic).flatMap { stoppedAt =>
                  if (stoppedAt - startedAt >= lifetime)
                    Pull.raiseError(ResponseBodyLifetimeTimeout(lifetime))
                  else
                    Pull.raiseError(ResponseBodyIdleTimeout(idleTimeout))
                }
              case None => Pull.done
            }
          }
        }

      stream.pull.timed(go).stream
    }

  private def withBodyTransform[F[_]: Async](
    client: Client[F],
    acquisitionTimeout: FiniteDuration,
    transformBody: Stream[F, Byte] => Stream[F, Byte]
  ): Client[F] =
    Client { req =>
      client
        .run(req)
        .timeout(acquisitionTimeout)
        .map(response => response.withBodyStream(transformBody(response.body)))
    }

  def withTimeout[F[_]: Async](client: Client[F], timeout: FiniteDuration): Client[F] =
    Client(req => client.run(req).timeout(timeout))

  def withResponseBodyTimeout[F[_]: Async](
    client: Client[F],
    acquisitionTimeout: FiniteDuration,
    responseTimeout: FiniteDuration
  ): Client[F] =
    withBodyTransform(
      client,
      acquisitionTimeout,
      body => timeoutResponseBody(body, acquisitionTimeout, responseTimeout)
    )
}
