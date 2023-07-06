package org.tessellation.currency.l0.http.routes

import cats.effect.Async
import cats.implicits.catsSyntaxEitherId
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.currency.dataApplication.DataUpdate
import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.currency.schema.currency.CurrencyBlock
import org.tessellation.kernel._
import org.tessellation.security.signature.Signed

import io.circe.Decoder
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class DataBlockRoutes[F[_]: Async](
  mkCell: Either[Signed[CurrencyBlock], Signed[DataApplicationBlock]] => Cell[F, StackF, _, Either[CellError, Ω], _]
)(implicit decoder: Decoder[DataUpdate])
    extends Http4sDsl[F] {
  import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "l1-data-output" =>
      req
        .as[Signed[DataApplicationBlock]]
        .map(_.asRight[Signed[CurrencyBlock]])
        .map(mkCell)
        .flatMap(_.run())
        .flatMap {
          case Left(_)  => BadRequest()
          case Right(_) => Ok()
        }
  }

  val publicRoutes: HttpRoutes[F] = Router(
    "/currency" -> httpRoutes
  )
}
