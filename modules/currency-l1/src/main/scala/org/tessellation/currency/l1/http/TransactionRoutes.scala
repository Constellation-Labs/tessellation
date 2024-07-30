package org.tessellation.currency.l1.http

import cats.effect.Async
import cats.syntax.all._

import org.tessellation.dag.l1.domain.transaction._
import org.tessellation.routes.internal._
import org.tessellation.schema.transaction.{Transaction, TransactionFee}
import org.tessellation.security.Hasher
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import io.circe.shapes._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl
import shapeless._
import shapeless.syntax.singleton._

final case class TransactionRoutes[F[_]: Async](
  transactionFeeEstimator: Option[TransactionFeeEstimator[F]],
  txHasher: Hasher[F]
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  protected val prefixPath: InternalUrlPrefix = "/transactions"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "estimate-fee" =>
      implicit val hasher: Hasher[F] = txHasher

      for {
        transaction <- req.as[Signed[Transaction]]
        hashedTransaction <- transaction.toHashed[F]
        fee <- transactionFeeEstimator match {
          case Some(estimator) => estimator.estimate(hashedTransaction)
          case None            => TransactionFee.zero.pure[F]
        }
        response <- Ok(("fee" ->> fee.value.value) :: HNil)
      } yield response
  }
}
