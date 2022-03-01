package org.tessellation.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.domain.snapshot.GlobalSnapshotStorage
import org.tessellation.ext.http4s.vars.SnapshotOrdinalVar
import org.tessellation.kryo.KryoSerializer

import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class GlobalSnapshotRoutes[F[_]: Async: KryoSerializer](globalSnapshotStorage: GlobalSnapshotStorage[F])
    extends Http4sDsl[F] {
  private val prefixPath = "/global-snapshot"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "latest" / "ordinal" =>
      import org.http4s.circe.CirceEntityCodec.circeEntityEncoder

      globalSnapshotStorage.head.map(_.map(_.ordinal)).flatMap {
        case Some(ordinal) => Ok(ordinal)
        case None          => NotFound()
      }

    case GET -> Root / "latest" =>
      import org.tessellation.ext.codecs.BinaryCodec.encoder

      globalSnapshotStorage.head.flatMap {
        case Some(snapshot) => Ok(snapshot)
        case _              => NotFound()
      }

    case GET -> Root / SnapshotOrdinalVar(ordinal) =>
      import org.tessellation.ext.codecs.BinaryCodec.encoder

      globalSnapshotStorage.get(ordinal).flatMap {
        case Some(snapshot) => Ok(snapshot)
        case _              => NotFound()
      }
  }

  val publicRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}
