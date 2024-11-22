package io.constellationnetwork.node.shared.http.routes

import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._

import io.constellationnetwork.currency.swap.ConsensusInput
import io.constellationnetwork.ext.http4s.{AddressVar, HashVar}
import io.constellationnetwork.node.shared.domain.cluster.storage.L0ClusterStorage
import io.constellationnetwork.node.shared.domain.swap._
import io.constellationnetwork.routes.internal.{InternalUrlPrefix, _}
import io.constellationnetwork.schema.http.{ErrorCause, ErrorResponse}
import io.constellationnetwork.schema.swap.{AllowSpend, AllowSpendStatus, AllowSpendView}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import io.circe.shapes._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger
import shapeless._
import shapeless.syntax.singleton._

final case class AllowSpendRoutes[F[_]: Async: Hasher: SecurityProvider](
  allowSpendConsensusInput: Queue[F, Signed[ConsensusInput.PeerConsensusInput]],
  l0ClusterStorage: L0ClusterStorage[F],
  allowSpendService: AllowSpendService[F],
  allowSpendStorage: AllowSpendStorage[F]
)(implicit S: Supervisor[F])
    extends Http4sDsl[F]
    with PublicRoutes[F]
    with P2PRoutes[F] {

  def logger = Slf4jLogger.getLogger[F]

  private val allowSpendLogger = Slf4jLogger.getLoggerFromName[F](allowSpendLoggerName)

  protected val prefixPath: InternalUrlPrefix = "/"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "allow-spends" =>
      for {
        transaction <- req.as[Signed[AllowSpend]]
        hashedTransaction <- transaction.toHashed[F]
        response <- allowSpendService
          .offer(hashedTransaction)
          .flatTap {
            case Left(errors) =>
              allowSpendLogger.warn(
                s"Received allowSpend hash=${hashedTransaction.hash} is invalid: ${transaction.show}, reason: ${errors.show}"
              )
            case Right(hash) => allowSpendLogger.info(s"Received valid allowSpend: ${hash.show}")
          }
          .flatMap {
            case Left(errors) => BadRequest(ErrorResponse(errors.map(e => ErrorCause(e.show))))
            case Right(hash)  => Ok(("hash" ->> hash.value) :: HNil)
          }
      } yield response

    case GET -> Root / "allow-spends" / HashVar(hash) =>
      allowSpendStorage.findWaiting(hash).flatMap {
        case Some(WaitingAllowSpend(tx)) => Ok(AllowSpendView(tx.signed.value, tx.hash, AllowSpendStatus.Waiting))
        case None                        => NotFound()
      }

    case GET -> Root / "allow-spends" / "last-reference" / AddressVar(address) =>
      allowSpendStorage
        .getLastProcessedAllowSpend(address)
        .map(_.ref)
        .flatMap(Ok(_))
  }

  protected val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "consensus" / "allow-spends" =>
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
          S.supervise(allowSpendConsensusInput.offer(consensusInput))
        }
        .flatMap(_ => Ok())
        .handleErrorWith { err =>
          logger.error(err)("An error occured") >> InternalServerError()
        }
  }
}
