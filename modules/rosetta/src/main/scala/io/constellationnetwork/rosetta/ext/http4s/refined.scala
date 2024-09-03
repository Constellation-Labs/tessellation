package io.constellationnetwork.rosetta.ext.http4s

import cats.MonadThrow
import cats.data.EitherT
import cats.syntax.applicativeError._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.option._

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.rosetta.domain.NetworkIdentifier
import io.constellationnetwork.rosetta.domain.error.RosettaError
import io.constellationnetwork.rosetta.domain.network.NetworkEnvironment

import io.circe._
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl

object refined {
  implicit class RefinedRosettaResponse[F[_]: MonadThrow, A](f: EitherT[F, RosettaError, A]) extends Http4sDsl[F] {
    def asRosettaResponse(implicit encoder: EntityEncoder[F, A]): F[Response[F]] =
      f.foldF(InternalServerError(_), Ok(_)).handleUnknownError
  }

  implicit class RefinedResponseErrorHandler[F[_]: MonadThrow](f: F[Response[F]]) extends Http4sDsl[F] {
    def handleUnknownError: F[Response[F]] =
      f.handleErrorWith { err =>
        InternalServerError[RosettaError](RosettaError.UnknownError(err))
      }
  }

  implicit class RefinedRequestRosettaDecoder[F[_]: JsonDecoder: MonadThrow](req: Request[F]) extends Http4sDsl[F] {
    def validateNetwork(appEnv: AppEnvironment, identifier: NetworkIdentifier)(f: => F[Response[F]]): F[Response[F]] =
      if (NetworkEnvironment.fromAppEnvironment(appEnv) === identifier.network.some && identifier.subNetworkIdentifier.isEmpty)
        f
      else InternalServerError[RosettaError](RosettaError.InvalidNetworkIdentifier)

    def decodeRosetta[A: Decoder](f: A => F[Response[F]]): F[Response[F]] =
      req.asJsonDecode[A].attempt.flatMap {
        case Left(e) =>
          val error: RosettaError = Option(e.getCause) match {
            case Some(c) => RosettaError.InvalidRequestBody(c.getMessage)
            case _       => RosettaError.UnprocessableEntity
          }
          InternalServerError(error)
        case Right(a) => f(a)
      }

    def decodeRosettaWithNetworkValidation[A: Decoder](appEnvironment: AppEnvironment, identifier: A => NetworkIdentifier)(
      f: A => F[Response[F]]
    ): F[Response[F]] =
      req.decodeRosetta[A] { a =>
        validateNetwork(appEnvironment, identifier(a)) {
          f(a)
        }
      }
  }
}
