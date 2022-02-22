package org.tessellation.http.routes

import cats.Monad

import org.tessellation.domain.snapshot.GlobalSnapshotStorage
import org.tessellation.ext.http4s.vars.SnapshotOrdinalVar

import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class GlobalSnapshotRoutes[F[_]: Monad](globalSnapshotStorage: GlobalSnapshotStorage[F])
    extends Http4sDsl[F] {
  private val prefixPath = "/global-snapshot"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "last" =>
      Ok(globalSnapshotStorage.getLast)

    case GET -> Root / SnapshotOrdinalVar(ordinal) =>
      Ok(globalSnapshotStorage.get(ordinal))
  }

  val publicRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}
