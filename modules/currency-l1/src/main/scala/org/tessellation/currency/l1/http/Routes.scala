package org.tessellation.currency.l1.http

import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._

import org.tessellation.currency.DataUpdate
import org.tessellation.dag.l1.domain.dataApplication.consensus.ConsensusInput
import org.tessellation.sdk.domain.cluster.storage.L0ClusterStorage
import org.tessellation.security.signature.Signed

import io.circe.Decoder
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class Routes[F[_]: Async](
  dataApplicationPeerConsensusInput: Queue[F, Signed[ConsensusInput.PeerConsensusInput]],
  l0ClusterStorage: L0ClusterStorage[F],
  dataDecoder: Decoder[DataUpdate]
)(implicit S: Supervisor[F])
    extends Http4sDsl[F] {

  def logger = Slf4jLogger.getLogger[F]

  private val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "consensus" / "data-application" =>
      implicit val decoder: Decoder[ConsensusInput.Proposal] = ConsensusInput.Proposal.decoder(dataDecoder)

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

  val p2pRoutes: HttpRoutes[F] = p2p
}
