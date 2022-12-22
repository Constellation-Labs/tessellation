package org.tessellation.dag.l1.http

import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.PeerBlockConsensusInput
import org.tessellation.dag.l1.domain.transaction.{TransactionService, TransactionStorage, transactionLoggerName}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ext.http4s.{AddressVar, HashVar}
import org.tessellation.schema.http.{ErrorCause, ErrorResponse}
import org.tessellation.schema.security.signature.Signed
import org.tessellation.schema.transaction.{Transaction, TransactionStatus, TransactionView}
import org.tessellation.sdk.domain.cluster.storage.L0ClusterStorage

import io.circe.shapes._
import io.circe.syntax.EncoderOps
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger
import shapeless._
import shapeless.syntax.singleton._

final case class Routes[F[_]: Async: KryoSerializer](
  transactionService: TransactionService[F],
  transactionStorage: TransactionStorage[F],
  l0ClusterStorage: L0ClusterStorage[F],
  peerBlockConsensusInputQueue: Queue[F, Signed[PeerBlockConsensusInput]]
)(implicit S: Supervisor[F])
    extends Http4sDsl[F] {

  private val transactionLogger = Slf4jLogger.getLoggerFromName[F](transactionLoggerName)

  private val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "transactions" =>
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
            case Left(errors) => BadRequest(ErrorResponse(errors.map(e => ErrorCause(e.show))).asJson)
            case Right(hash)  => Ok(("hash" ->> hash.value) :: HNil)
          }
      } yield response

    case GET -> Root / "transactions" / HashVar(hash) =>
      transactionStorage.find(hash).flatMap {
        case Some(tx) => Ok(TransactionView(tx.signed.value, tx.hash, TransactionStatus.Waiting).asJson)
        case None     => NotFound()
      }

    case GET -> Root / "transactions" / "last-reference" / AddressVar(address) =>
      transactionStorage
        .getLastAcceptedReference(address)
        .flatMap(Ok(_))

    case GET -> Root / "l0" / "peers" =>
      l0ClusterStorage.getPeers.flatMap(Ok(_))
  }

  private val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "consensus" / "data" =>
      for {
        peerBlockConsensusInput <- req.as[Signed[PeerBlockConsensusInput]]
        _ <- S.supervise(peerBlockConsensusInputQueue.offer(peerBlockConsensusInput))
        response <- Ok()
      } yield response
  }

  val publicRoutes: HttpRoutes[F] = public

  val p2pRoutes: HttpRoutes[F] = p2p
}
