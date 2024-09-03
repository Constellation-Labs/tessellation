package io.constellationnetwork.dag.l0.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.kernel._
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.Block
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl

final case class DAGBlockRoutes[F[_]: Async](
  mkCell: Signed[Block] => Cell[F, StackF, _, Either[CellError, Ω], _]
) extends Http4sDsl[F]
    with PublicRoutes[F] {
  import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

  protected val prefixPath: InternalUrlPrefix = "/dag"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "l1-output" =>
      req
        .as[Signed[Block]]
        .map(mkCell)
        .flatMap(_.run())
        .flatMap(_ => Ok())
  }
}
