package org.tessellation.currency.l1.http

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._

import org.tessellation.currency.dataApplication._
import org.tessellation.currency.l1.domain.error.{GL0SnapshotOrdinalUnavailable, InvalidDataUpdate, InvalidSignature}
import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.error.ApplicationError._
import org.tessellation.ext.http4s.error._
import org.tessellation.node.shared.domain.cluster.storage.L0ClusterStorage
import org.tessellation.node.shared.domain.queue.ViewableQueue
import org.tessellation.node.shared.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.routes.internal._
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import io.circe.shapes._
import io.circe.{Decoder, Encoder, Json}
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl
import org.http4s.{EntityDecoder, HttpRoutes, Response}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import shapeless._
import shapeless.syntax.singleton._

final case class DataApplicationRoutes[F[_]: Async: Hasher: SecurityProvider: L1NodeContext](
  dataApplicationPeerConsensusInput: Queue[F, Signed[ConsensusInput.PeerConsensusInput]],
  l0ClusterStorage: L0ClusterStorage[F],
  dataApplication: BaseDataApplicationL1Service[F],
  dataUpdatesQueue: ViewableQueue[F, Signed[DataUpdate]],
  lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  lastCurrencySnapshotStorage: LastSnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]
)(implicit S: Supervisor[F])
    extends Http4sDsl[F]
    with PublicRoutes[F]
    with P2PRoutes[F] {

  def logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  protected val prefixPath: InternalUrlPrefix = "/"

  private def validate(update: Signed[DataUpdate])(onValid: F[Response[F]]): F[Response[F]] =
    lastGlobalSnapshotStorage.getOrdinal.flatMap {
      _.traverse(ord => (dataApplication.validateFee(ord)(update), dataApplication.validateUpdate(update.value)).mapN(_ |+| _))
    }.flatMap {
      case None             => InternalServerError(GL0SnapshotOrdinalUnavailable.toApplicationError)
      case Some(Invalid(e)) => BadRequest(InvalidDataUpdate(e.toString).toApplicationError)
      case Some(Valid(_))   => onValid
    }

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "data" / "validate" =>
      implicit val decoder: EntityDecoder[F, Signed[DataUpdate]] = dataApplication.signedDataEntityDecoder
      req
        .asR[Signed[DataUpdate]](validate(_)(Ok(Json.obj())))
        .handleUnknownError

    case GET -> Root / "data" =>
      implicit val signedEncoder: Encoder[Signed[DataUpdate]] = Signed.encoder(dataApplication.dataEncoder)
      dataUpdatesQueue.view.flatMap(Ok(_))

    case req @ POST -> Root / "data" =>
      implicit val decoder: EntityDecoder[F, Signed[DataUpdate]] = dataApplication.signedDataEntityDecoder
      req
        .asR[Signed[DataUpdate]] {
          _.toHashedWithSignatureCheck[F](dataApplication.serializeUpdate _).flatMap {
            case Left(_) => BadRequest(InvalidSignature.toApplicationError)
            case Right(hashed) =>
              validate(hashed.signed) {
                dataUpdatesQueue.offer(hashed.signed) >> Ok(("hash" ->> hashed.hash.value) :: HNil)
              }
          }
        }
        .handleUnknownError

    case GET -> Root / "l0" / "peers" =>
      l0ClusterStorage.getPeers.flatMap(Ok(_))
  }

  protected val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "consensus" / "data-application" =>
      implicit val decoder: Decoder[ConsensusInput.Proposal] = ConsensusInput.Proposal.decoder(dataApplication.dataDecoder)

      req
        .as[Signed[ConsensusInput.Proposal]]
        .map(_.asInstanceOf[Signed[ConsensusInput.PeerConsensusInput]])
        .handleErrorWith { _ =>
          req
            .as[Signed[ConsensusInput.SignatureProposal]]
            .map(_.asInstanceOf[Signed[ConsensusInput.PeerConsensusInput]])
            .handleErrorWith { _ =>
              req
                .as[Signed[ConsensusInput.CancelledCreationRound]]
                .map(_.asInstanceOf[Signed[ConsensusInput.PeerConsensusInput]])
            }
        }
        .flatMap { consensusInput =>
          S.supervise(dataApplicationPeerConsensusInput.offer(consensusInput))
        }
        .flatMap(_ => Ok())
        .handleErrorWith { err =>
          logger.error(err)(s"An error occured") >> InternalServerError()
        }
  }
}
