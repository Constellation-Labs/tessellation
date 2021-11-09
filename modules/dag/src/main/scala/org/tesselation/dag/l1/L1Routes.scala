package org.tesselation.dag.l1

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tesselation.ext.crypto._
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.transaction.Transaction
import org.tesselation.security.signature.Signed

import io.circe.syntax.EncoderOps
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl

final case class L1Routes[F[_]: Async: KryoSerializer](
  transactionValidator: TransactionValidator[F],
  transactionQueue: Queue[F, Signed[Transaction]]
) extends Http4sDsl[F] {

  val routes: HttpRoutes[F] = HttpRoutes
    .of[F] {
      case req @ POST -> Root / "transaction" =>
        for {
          signedTransaction <- req.as[Signed[Transaction]]
          validation <- transactionValidator.validateSingleTransaction(signedTransaction)
          response <- validation match {
            case Valid(signedTx) =>
              transactionQueue
              // TODO: we could move the queue out of the L1Routes and instead have it in TransactionManager along with validator
                .offer(signedTx)
                // TODO: I don't like it regenareting hash here, maybe validator should returned a Hashed[Transaction]
                .flatMap(_ => signedTx.value.hashF)
                .map(_.asJson)
                .flatMap(Ok(_))
            case Invalid(e) =>
              BadRequest(e.toString.asJson)
          }
        } yield response
    }
}
