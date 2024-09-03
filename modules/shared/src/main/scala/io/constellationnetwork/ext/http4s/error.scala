package io.constellationnetwork.ext.http4s

import cats.MonadThrow
import cats.data.EitherT
import cats.syntax.all._

import io.constellationnetwork.error.ApplicationError

import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl

object error {
  implicit class RefinedApplicationResponse[F[_]: MonadThrow, A](f: EitherT[F, ApplicationError, A]) extends Http4sDsl[F] {
    def asApplicationResponse(implicit encoder: EntityEncoder[F, A]): F[Response[F]] =
      f.foldF(InternalServerError(_), Ok(_)).handleUnknownError
  }

  implicit class RefinedResponseErrorHandler[F[_]: MonadThrow](f: F[Response[F]]) extends Http4sDsl[F] {
    def handleUnknownError: F[Response[F]] =
      f.handleErrorWith { err =>
        InternalServerError[ApplicationError](ApplicationError.UnknownError(err))
      }
  }

  implicit class RefinedRequestApplicationDecoder[F[_]: JsonDecoder: MonadThrow](req: Request[F]) extends Http4sDsl[F] {
    def asR[A](f: A => F[Response[F]])(implicit d: EntityDecoder[F, A]): F[Response[F]] =
      req.as[A].attempt.flatMap {
        case Left(e) =>
          val error: ApplicationError = Option(e.getCause) match {
            case Some(c) => ApplicationError.InvalidRequestBody(c.getMessage)
            case _       => ApplicationError.UnprocessableEntity
          }
          InternalServerError(error)
        case Right(a) => f(a)
      }
  }
}
