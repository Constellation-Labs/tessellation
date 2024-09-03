package io.constellationnetwork.currency.l0.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationBlock
import io.constellationnetwork.kernel._
import io.constellationnetwork.node.shared.snapshot.currency.{CurrencySnapshotEvent, DataApplicationBlockEvent}
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import io.circe.{Decoder, Encoder}
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.Http4sDsl

final case class DataBlockRoutes[F[_]: Async](
  mkCell: CurrencySnapshotEvent => Cell[F, StackF, _, Either[CellError, Ω], _],
  dataApplication: BaseDataApplicationL0Service[F]
)(implicit context: L0NodeContext[F], decoder: Decoder[DataUpdate], encoder: Encoder[DataCalculatedState])
    extends Http4sDsl[F]
    with PublicRoutes[F]
    with P2PRoutes[F] {
  import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

  protected val prefixPath: InternalUrlPrefix = "/currency"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "l1-data-output" =>
      req
        .as[Signed[DataApplicationBlock]]
        .map(DataApplicationBlockEvent(_))
        .map(mkCell)
        .flatMap(_.run())
        .flatMap {
          case Left(_)  => BadRequest()
          case Right(_) => Ok()
        }
  }

  protected val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "state" / "calculated" =>
      dataApplication.getCalculatedState
        .flatMap(Ok(_))

  }
}
