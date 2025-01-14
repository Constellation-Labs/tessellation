package io.constellationnetwork.currency.l0.http.routes

import cats.effect.Async
import cats.implicits.catsSyntaxApplicativeId
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
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, Response}

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
      for {
        signedBlock <- req.as[Signed[DataApplicationBlock]]
        dataState <- loadDataState
        isValid <- dataApplication.validateData(dataState, signedBlock.updates).map(_.isValid)
        resp <- if (isValid) handleSuccess(signedBlock) else BadRequest()
      } yield resp
  }

  protected val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "state" / "calculated" =>
      dataApplication.getCalculatedState
        .flatMap(Ok(_))
  }

  private def loadDataState: F[DataState[DataOnChainState, DataCalculatedState]] =
    for {
      maybeHashCis <- context.getLastCurrencySnapshot
      hashCis <- Async[F].fromOption(maybeHashCis, new RuntimeException("Failed to access Currency incremental snapshot"))
      part <- Async[F].fromOption(hashCis.dataApplication, new RuntimeException("Failed to access Data application part"))
      onchain <- dataApplication.deserializeState(part.onChainState).flatMap(Async[F].fromEither)
      (_, calc) <- dataApplication.getCalculatedState
    } yield DataState(onchain, calc)

  private def handleSuccess(signedBlock: Signed[DataApplicationBlock]): F[Response[F]] =
    DataApplicationBlockEvent(signedBlock)
      .pure[F]
      .map(mkCell)
      .flatMap(_.run())
      .flatMap {
        case Left(_)  => BadRequest()
        case Right(_) => Ok()
      }
}
