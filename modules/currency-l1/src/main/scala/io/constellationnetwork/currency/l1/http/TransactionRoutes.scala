package io.constellationnetwork.currency.l1.http

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.dag.l1.domain.transaction._
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.transaction.{Transaction, TransactionFee}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import io.circe.syntax.EncoderOps
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl

final case class TransactionRoutes[F[_]: Async](
  transactionFeeEstimator: Option[TransactionFeeEstimator[F]],
  txHasher: Hasher[F]
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  protected val prefixPath: InternalUrlPrefix = "/transactions"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "estimate-fee" =>
      implicit val hasher = txHasher

      for {
        transaction <- req.as[Signed[Transaction]]
        hashedTransaction <- transaction.toHashed[F]
        fee <- transactionFeeEstimator match {
          case Some(estimator) => estimator.estimate(hashedTransaction)
          case None            => TransactionFee.zero.pure[F]
        }
        response <- Ok(("fee" -> fee.value.value).asJson)
      } yield response
  }
}
