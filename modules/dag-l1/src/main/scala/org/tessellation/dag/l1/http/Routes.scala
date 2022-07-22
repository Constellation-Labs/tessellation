package org.tessellation.dag.l1.http

import cats.effect.std.Queue
import cats.effect.{Async, Spawn}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.PeerBlockConsensusInput
import org.tessellation.dag.l1.domain.transaction.{TransactionService, TransactionStorage}
import org.tessellation.ext.http4s.{AddressVar, HashVar}
import org.tessellation.schema.http.{ErrorCause, ErrorResponse, SuccessResponse}
import org.tessellation.schema.transaction.{Transaction, TransactionStatus, TransactionView}
import org.tessellation.security.signature.Signed

import io.circe.shapes._
import io.circe.syntax.EncoderOps
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import shapeless._
import shapeless.syntax.singleton._

final case class Routes[F[_]: Async](
  transactionService: TransactionService[F],
  transactionStorage: TransactionStorage[F],
  peerBlockConsensusInputQueue: Queue[F, Signed[PeerBlockConsensusInput]]
) extends Http4sDsl[F] {

  private val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "transactions" =>
      for {
        transaction <- req.as[Signed[Transaction]]
        response <- transactionService.offer(transaction).flatMap {
          case Left(errors) => BadRequest(ErrorResponse(errors.map(e => ErrorCause(e.show))).asJson)
          case Right(hash) =>
            val response = ("hash" ->> hash.value) :: HNil
            Ok(SuccessResponse(response))
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
  }

  private val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "consensus" / "data" =>
      for {
        peerBlockConsensusInput <- req.as[Signed[PeerBlockConsensusInput]]
        _ <- Spawn[F].start(peerBlockConsensusInputQueue.offer(peerBlockConsensusInput))
        response <- Ok()
      } yield response
  }

  val publicRoutes: HttpRoutes[F] = public

  val p2pRoutes: HttpRoutes[F] = p2p
}
