package io.constellationnetwork.currency.l1.http

import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._

import io.constellationnetwork.currency.l1.domain.error.InvalidSignature
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.currency.swap.ConsensusInput
import io.constellationnetwork.error.ApplicationError._
import io.constellationnetwork.ext.http4s.error._
import io.constellationnetwork.node.shared.domain.cluster.storage.L0ClusterStorage
import io.constellationnetwork.node.shared.domain.queue.ViewableQueue
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.routes.internal.{InternalUrlPrefix, _}
import io.constellationnetwork.schema.swap.SwapTransaction
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import io.circe.shapes._
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, Response}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import shapeless._
import shapeless.syntax.singleton._

final case class SwapRoutes[F[_]: Async: Hasher: SecurityProvider](
  swapPeerConsensusInput: Queue[F, Signed[ConsensusInput.PeerConsensusInput]],
  l0ClusterStorage: L0ClusterStorage[F],
  swapTransactionsQueue: ViewableQueue[F, Signed[SwapTransaction]],
  lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  lastCurrencySnapshotStorage: LastSnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]
)(implicit S: Supervisor[F])
    extends Http4sDsl[F]
    with PublicRoutes[F]
    with P2PRoutes[F] {

  def logger = Slf4jLogger.getLogger[F]

  protected val prefixPath: InternalUrlPrefix = "/"

  private def validate(tx: Signed[SwapTransaction])(onValid: F[Response[F]]): F[Response[F]] =
    // TODO: @mwadon - validation
    onValid

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "swap" =>
      swapTransactionsQueue.view.flatMap(Ok(_))

    case req @ POST -> Root / "swap" =>
      req
        .asR[Signed[SwapTransaction]] {
          _.toHashedWithSignatureCheck[F].flatMap {
            case Left(_) => BadRequest(InvalidSignature.toApplicationError)
            case Right(hashed) =>
              validate(hashed.signed) {
                swapTransactionsQueue.offer(hashed.signed) >> Ok(("hash" ->> hashed.hash.value) :: HNil)
              }
          }
        }
        .handleUnknownError
  }

  protected val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "consensus" / "swap" =>
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
          S.supervise(swapPeerConsensusInput.offer(consensusInput))
        }
        .flatMap(_ => Ok())
        .handleErrorWith { err =>
          logger.error(err)("An error occured") >> InternalServerError()
        }
  }
}
