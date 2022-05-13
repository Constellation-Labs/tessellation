package org.tessellation.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.domain.block.L1Output
import org.tessellation.domain.cell.{L0Cell, L0CellInput}
import org.tessellation.security.signature.Signed

import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class BlockRoutes[F[_]: Async](mkDagCell: L0Cell.Mk[F]) extends Http4sDsl[F] {

  private[routes] val prefixPath = "/l1-output"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root =>
      req
        .as[Signed[L1Output]]
        .map(L0CellInput.HandleDAGL1(_))
        .map(mkDagCell)
        .flatMap(_.run())
        .flatMap(_ => Ok())
  }

  val publicRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}
