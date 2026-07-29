package io.constellationnetwork.currency.l1.http

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.dag.l1.domain.transaction._
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.transaction.{Transaction, TransactionFee}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.Signed.{ProofsHasher, SignedHasher}

import eu.timepit.refined.auto._
import io.circe.Json
import io.circe.syntax._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl

final case class TransactionRoutes[F[_]: Async](
  transactionFeeEstimator: Option[TransactionFeeEstimator[F]],
  signatureHasher: Hasher[F],
  bodyHasher: Hasher[F]
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  protected val prefixPath: InternalUrlPrefix = "/transactions"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "estimate-fee" =>
      for {
        transaction <- req.as[Signed[Transaction]]
        hashedTransaction <- {
          implicit val kryoHasher: SignedHasher[F] = SignedHasher(signatureHasher)
          implicit val proofsHasher: ProofsHasher[F] = ProofsHasher(bodyHasher)
          transaction.toHashedHybrid[F]
        }
        fee <- transactionFeeEstimator match {
          case Some(estimator) => estimator.estimate(hashedTransaction)
          case None            => TransactionFee.zero.pure[F]
        }
        // Plain Json.obj instead of the shapeless singleton-record encoder
        // (`("fee" ->> ...) :: HNil` with io.circe.shapes): the CI Build JARs job crashes the
        // scalac 2.13.18 backend on the singleton-typed record it produces ("assertion failed:
        // type R" / "ClassBType.info not yet assigned" while emitting this file). Identical
        // JSON: {"fee": <long>}.
        response <- Ok(Json.obj("fee" -> fee.value.value.asJson))
      } yield response
  }
}
