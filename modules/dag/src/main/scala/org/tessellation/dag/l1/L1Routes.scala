package org.tessellation.dag.l1

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.l1.storage.TransactionStorage
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed._

import io.circe.syntax.EncoderOps
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl

final case class L1Routes[F[_]: Async: KryoSerializer: SecurityProvider](
  transactionValidator: TransactionValidator[F],
  transactionStorage: TransactionStorage[F],
  l1DAGBlockDataQueue: Queue[F, L1PeerDAGBlockData],
  l1PeerDAGBlockQueue: Queue[F, FinalBlock]
) extends Http4sDsl[F] {

  val routes: HttpRoutes[F] = HttpRoutes
    .of[F] {
      case req @ POST -> Root / "transaction" =>
        for {
          signedTransaction <- req.as[Signed[Transaction]]
          validation <- transactionValidator.validateSingleTransaction(signedTransaction)
          response <- validation match {
            case Valid(signedTx) =>
              transactionStorage
                .putTransactions(Set(signedTransaction))
                // TODO: I don't like it regenerating hash here, maybe validator should returned a Hashed[Transaction]
                .flatMap(_ => signedTx.value.hashF)
                .map(_.asJson)
                .flatMap(Ok(_))
            case Invalid(e) =>
              BadRequest(e.toString.asJson)
          }
        } yield response

      case req @ POST -> Root / "block" =>
        for {
          signedBlock <- req.as[Signed[DAGBlock]]
          _ <- Async[F].start {
            signedBlock.hashWithSignatureCheck.map {
              case Left(_) =>
                Async[F].unit
              case Right(hashedBlock) =>
                // TODO: we could have different types for own blocks and incoming blocks
                //  e.g. OwnBlock, PeerBlock instead of single FinalBlock if that would give some value
                l1PeerDAGBlockQueue.offer(FinalBlock(hashedBlock))
            }
          }
          response <- Ok()
        } yield response

      case req @ POST -> Root / "consensus" / "data" =>
        for {
          signedDAGBlockData <- req.as[Signed[L1PeerDAGBlockData]]
          _ <- Async[F].start {
            for {
              hasValidSignature <- signedDAGBlockData.hasValidSignature
              isSignedBy = signedDAGBlockData.isSignedBy(Set(PeerId._Id.get(signedDAGBlockData.value.senderId)))
              _ <- (hasValidSignature && isSignedBy)
                .pure[F]
                .ifM(
                  l1DAGBlockDataQueue.offer(signedDAGBlockData.value),
                  Async[F].unit
                )
            } yield ()
          }
          response <- Ok()
        } yield response
    }
}
