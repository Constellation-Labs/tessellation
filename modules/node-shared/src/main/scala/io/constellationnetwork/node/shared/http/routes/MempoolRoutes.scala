package io.constellationnetwork.node.shared.http.routes

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.routes.internal._

import derevo.circe.magnolia.encoder
import derevo.derive
import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl

/** HTTP routes for mempool status and information.
  *
  * Provides endpoints to query mempool state for monitoring and debugging.
  *
  * @tparam Key
  *   The state key type (unused by routes, but required for EventMempool type)
  */
class MempoolRoutes[F[_]: Async, Event, Key](
  mempool: EventMempool[F, Event, Key]
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  protected val prefixPath: InternalUrlPrefix = "/mempool"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "info" =>
      getInfo.flatMap(Ok(_))

    case GET -> Root / "size" =>
      mempool.size.flatMap(s => Ok(MempoolSize(s)))
  }

  private def getInfo: F[MempoolInfo] =
    for {
      size <- mempool.size
      snapshot <- mempool.snapshot()
    } yield
      MempoolInfo(
        size = size,
        hashCount = snapshot.hashes.size
      )
}

object MempoolRoutes {

  def make[F[_]: Async, Event, Key](
    mempool: EventMempool[F, Event, Key]
  ): MempoolRoutes[F, Event, Key] =
    new MempoolRoutes[F, Event, Key](mempool)
}

@derive(encoder)
case class MempoolInfo(
  size: Int,
  hashCount: Int
)

@derive(encoder)
case class MempoolSize(
  size: Int
)
