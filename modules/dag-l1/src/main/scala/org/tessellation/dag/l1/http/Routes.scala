package org.tessellation.dag.l1.http

import cats.effect.std.Queue
import cats.effect.{Async, Spawn}
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.PeerBlockConsensusInput
import org.tessellation.dag.l1.domain.transaction.{TransactionService, TransactionStorage}
import org.tessellation.ext.http4s.AddressVar
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed

import io.circe.syntax.EncoderOps
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl

final case class Routes[F[_]: Async](
  transactionService: TransactionService[F],
  transactionStorage: TransactionStorage[F],
  peerBlockConsensusInputQueue: Queue[F, Signed[PeerBlockConsensusInput]]
) extends Http4sDsl[F] {

  private val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "transaction" =>
      for {
        transaction <- req.as[Signed[Transaction]]
        response <- transactionService.offer(transaction).flatMap {
          case Left(e)     => BadRequest(e.show.asJson)
          case Right(hash) => Ok(hash.asJson)
        }
      } yield response

    case GET -> Root / "transaction" / "last-reference" / AddressVar(address) =>
      transactionStorage.getLastAcceptedReference(address)
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
