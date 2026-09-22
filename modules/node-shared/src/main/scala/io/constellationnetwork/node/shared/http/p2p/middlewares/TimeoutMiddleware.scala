package io.constellationnetwork.node.shared.http.p2p.middlewares

import java.util.concurrent.TimeoutException

import cats.effect.Async
import cats.effect.implicits.genTemporalOps

import scala.concurrent.duration.FiniteDuration

import fs2.{Pull, Stream}
import org.http4s.client.Client

object TimeoutMiddleware {
  private def timeoutBetweenChunks[F[_]: Async, A](stream: Stream[F, A], timeout: FiniteDuration): Stream[F, A] = {
    def go(pull: Pull.Timed[F, A]): Pull[F, A, Unit] =
      pull.timeout(timeout) >> pull.uncons.flatMap {
        case Some((Right(chunk), next)) => Pull.output(chunk) >> go(next)
        case Some((Left(_), _))         => Pull.raiseError(new TimeoutException(s"Timed out waiting $timeout for response body data"))
        case None                       => Pull.done
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
      body => timeoutBetweenChunks(body, acquisitionTimeout).timeout(responseTimeout)
    )
}
