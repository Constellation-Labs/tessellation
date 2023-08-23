package org.tessellation.currency.l0.http.routes

import cats.effect.Async
import cats.implicits.catsSyntaxEitherId
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.http.routes.internal.{InternalUrlPrefix, PublicRoutes}
import org.tessellation.kernel._
import org.tessellation.schema.Block
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl

final case class CurrencyBlockRoutes[F[_]: Async](
  mkCell: Either[Signed[Block], Signed[DataApplicationBlock]] => Cell[F, StackF, _, Either[CellError, Ω], _]
) extends Http4sDsl[F]
    with PublicRoutes[F] {
  import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

  protected val prefixPath: InternalUrlPrefix = "/currency"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "l1-output" =>
      req
        .as[Signed[Block]]
        .map(_.asLeft[Signed[DataApplicationBlock]])
        .map(mkCell)
        .flatMap(_.run())
        .flatMap(_ => Ok())
  }
}
