package io.constellationnetwork.dag.l1.http

import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import io.constellationnetwork.currency.swap.{ConsensusInput => SwapConsensusInput}
import io.constellationnetwork.dag.l1.domain.consensus.block.BlockConsensusInput.PeerBlockConsensusInput
import io.constellationnetwork.dag.l1.domain.transaction._
import io.constellationnetwork.ext.http4s.{AddressVar, HashVar}
import io.constellationnetwork.node.shared.domain.cluster.storage.L0ClusterStorage
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.http.{ErrorCause, ErrorResponse}
import io.constellationnetwork.schema.transaction.{Transaction, TransactionStatus, TransactionView}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import io.circe.shapes._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger
import shapeless._
import shapeless.syntax.singleton._

final case class Routes[F[_]: Async](
  transactionService: TransactionService[F],
  transactionStorage: TransactionStorage[F],
  l0ClusterStorage: L0ClusterStorage[F],
  peerBlockConsensusInputQueue: Queue[F, Signed[PeerBlockConsensusInput]],
  swapPeerConsensusInputQueue: Queue[F, Signed[SwapConsensusInput.PeerConsensusInput]],
  txHasher: Hasher[F]
)(implicit S: Supervisor[F])
    extends Http4sDsl[F]
    with PublicRoutes[F]
    with P2PRoutes[F] {

  private val transactionLogger = Slf4jLogger.getLoggerFromName[F](transactionLoggerName)

  protected val prefixPath: InternalUrlPrefix = "/"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "transactions" =>
      implicit val hasher = txHasher

      for {
        transaction <- req.as[Signed[Transaction]]
        hashedTransaction <- transaction.toHashed[F]
        response <- transactionService
          .offer(hashedTransaction)
          .flatTap {
            case Left(errors) =>
              transactionLogger.warn(
                s"Received transaction hash=${hashedTransaction.hash} is invalid: ${transaction.show}, reason: ${errors.show}"
              )
            case Right(hash) => transactionLogger.info(s"Received valid transaction: ${hash.show}")
          }
          .flatMap {
            case Left(errors) => BadRequest(ErrorResponse(errors.map(e => ErrorCause(e.show))))
            case Right(hash)  => Ok(("hash" ->> hash.value) :: HNil)
          }
      } yield response

    case GET -> Root / "transactions" / HashVar(hash) =>
      transactionStorage.findWaiting(hash).flatMap {
        case Some(WaitingTx(tx)) => Ok(TransactionView(tx.signed.value, tx.hash, TransactionStatus.Waiting))
        case None                => NotFound()
      }

    case GET -> Root / "transactions" / "last-reference" / AddressVar(address) =>
      transactionStorage
        .getLastProcessedTransaction(address)
        .map(_.ref)
        .flatMap(Ok(_))

    case GET -> Root / "l0" / "peers" =>
      l0ClusterStorage.getPeers.flatMap(Ok(_))
  }

  protected val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "consensus" / "data" =>
      for {
        peerBlockConsensusInput <- req.as[Signed[PeerBlockConsensusInput]]
        _ <- S.supervise(peerBlockConsensusInputQueue.offer(peerBlockConsensusInput))
        response <- Ok()
      } yield response
  }
}
