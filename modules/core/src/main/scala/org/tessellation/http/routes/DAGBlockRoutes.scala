package org.tessellation.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.kernel._
import org.tessellation.schema.Block
import org.tessellation.security.signature.Signed

import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class DAGBlockRoutes[F[_]: Async](
  mkCell: Signed[Block] => Cell[F, StackF, _, Either[CellError, Ω], _]
) extends Http4sDsl[F] {
  import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "l1-output" =>
      req
        .as[Signed[Block]]
        .map(mkCell)
        .flatMap(_.run())
        .flatMap(_ => Ok())
  }

  val publicRoutes: HttpRoutes[F] = Router(
    "/dag" -> httpRoutes
  )
}
