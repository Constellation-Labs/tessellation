package org.tessellation.dag.l0.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.l0.infrastructure.trust.storage.TrustStorage.TrustStore
import org.tessellation.node.shared.domain.trust.storage.TrustStorage
import org.tessellation.routes.internal._

import eu.timepit.refined.auto._
import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl

final case class DebugTrustRoutes[F[_]: Async](
  trustStorage: TrustStorage[F]
) extends Http4sDsl[F]
    with PublicRoutes[F] {
  protected val prefixPath: InternalUrlPrefix = "/"

  val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "trust" / "latest" =>
      val output = for {
        trust <- trustStorage.getTrust
        current <- trustStorage.getCurrentOrdinalTrust
        next <- trustStorage.getNextOrdinalTrust
      } yield TrustStore(trust, current, next)

      output.flatMap(Ok(_))
  }
}
