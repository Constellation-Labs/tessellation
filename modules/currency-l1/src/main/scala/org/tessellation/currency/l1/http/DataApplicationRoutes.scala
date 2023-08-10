package org.tessellation.currency.l1.http

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._

import org.tessellation.currency.dataApplication._
import org.tessellation.currency.l1.domain.error.{InvalidDataUpdate, InvalidSignature}
import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.ext.http4s.error._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.sdk.domain.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import io.circe.Decoder
import io.circe.shapes._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger
import shapeless._
import shapeless.syntax.singleton._

final case class DataApplicationRoutes[F[_]: Async: KryoSerializer: SecurityProvider: L1NodeContext](
  dataApplicationPeerConsensusInput: Queue[F, Signed[ConsensusInput.PeerConsensusInput]],
  l0ClusterStorage: L0ClusterStorage[F],
  dataApplication: BaseDataApplicationL1Service[F],
  dataUpdatesQueue: Queue[F, Signed[DataUpdate]],
  lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  lastCurrencySnapshotStorage: LastSnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]
)(implicit S: Supervisor[F])
    extends Http4sDsl[F] {

  def logger = Slf4jLogger.getLogger[F]

  private val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "data" =>
      implicit val decoder = dataApplication.signedDataEntityDecoder

      req
        .asR[Signed[DataUpdate]] {
          _.toHashedWithSignatureCheck[F](dataApplication.serializeUpdate _).flatMap {
            case Left(_) => InternalServerError(InvalidSignature.toApplicationError)
            case Right(hashed) =>
              dataApplication
                .validateUpdate(hashed.signed.value)
                .flatMap {
                  case Invalid(e) => InternalServerError(InvalidDataUpdate(e.toString).toApplicationError)
                  case Valid(_) =>
                    dataUpdatesQueue.offer(hashed.signed) >>
                      Ok(("hash" ->> hashed.hash.value) :: HNil)
                }
          }
        }
        .handleUnknownError

    case GET -> Root / "l0" / "peers" =>
      l0ClusterStorage.getPeers.flatMap(Ok(_))
  }

  private val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
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

  val publicRoutes: HttpRoutes[F] = public
  val p2pRoutes: HttpRoutes[F] = p2p
}
