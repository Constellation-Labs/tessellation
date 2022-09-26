package org.tessellation.ext.http4s.headers

import cats.Applicative
import cats.syntax.foldable._

import org.http4s.Status.UnsupportedMediaType
import org.http4s._
import org.http4s.headers.`Content-Length`

object negotiation {

  def resolveEncoder[F[_]: Applicative, A](request: Request[F])(route: EntityEncoder[F, A] => F[Response[F]])(
    implicit encoders: List[EntityEncoder[F, A]]
  ): F[Response[F]] = {
    val resp = Response[F](status = UnsupportedMediaType, headers = Headers(List(`Content-Length`.zero)))

    findEncoder(encoders, request)
      .map(route(_))
      .getOrElse(Applicative[F].pure(resp))
  }

  private def findEncoder[F[_], A](encoders: List[EntityEncoder[F, A]], req: Request[F]): Option[EntityEncoder[F, A]] =
    req.headers.get[org.http4s.headers.Accept].flatMap { acceptHeader =>
      acceptHeader.values.collectFirstSome { mediaRangeAndQValue =>
        encoders.find(_.contentType.exists(ct => mediaRangeAndQValue.mediaRange.satisfiedBy(ct.mediaType)))
      }
    }
}
