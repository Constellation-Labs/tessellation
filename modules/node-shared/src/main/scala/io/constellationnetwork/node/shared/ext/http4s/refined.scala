package io.constellationnetwork.node.shared.ext.http4s

import cats.MonadThrow
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._

import eu.timepit.refined.api.{Refined, Validate}
import eu.timepit.refined.refineV
import io.circe.Decoder
import io.circe.syntax.EncoderOps
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl

object refined {
  implicit def refinedQueryParamDecoder[T: QueryParamDecoder, P](
    implicit ev: Validate[T, P]
  ): QueryParamDecoder[T Refined P] =
    QueryParamDecoder[T].emap(refineV[P](_).leftMap(m => ParseFailure(m, m)))

  implicit class RefinedRequestDecoder[F[_]: JsonDecoder: MonadThrow](req: Request[F]) extends Http4sDsl[F] {

    def decodeR[A: Decoder](f: A => F[Response[F]]): F[Response[F]] =
      req.asJsonDecode[A].attempt.flatMap {
        case Left(e) =>
          Option(e.getCause) match {
            case Some(c) => BadRequest(c.getMessage.asJson)
            case _       => UnprocessableEntity()
          }
        case Right(a) => f(a)
      }
  }
}
