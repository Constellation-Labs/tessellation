package org.tessellation.sdk.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.ext.codecs.BinaryCodec
import org.tessellation.ext.http4s.HashVar
import org.tessellation.ext.http4s.headers.negotiation.resolveEncoder
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.GlobalSnapshot
import org.tessellation.schema.snapshot.{Snapshot, SnapshotMetadata}
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.ext.http4s.SnapshotOrdinalVar
import org.tessellation.sdk.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage
import org.tessellation.security.signature.Signed

import io.circe.Encoder
import io.circe.shapes._
import org.http4s.circe.CirceEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.http4s.{EntityEncoder, HttpRoutes}
import shapeless.HNil
import shapeless.syntax.singleton._

final case class SnapshotRoutes[F[_]: Async: KryoSerializer, S <: Snapshot[_, _]: Encoder, C: Encoder](
  snapshotStorage: SnapshotStorage[F, S, C],
  fullGlobalSnapshotStorage: Option[SnapshotLocalFileSystemStorage[F, GlobalSnapshot]],
  prefix: String
) extends Http4sDsl[F] {

  object FullSnapshotQueryParam extends FlagQueryParamMatcher("full")

  // first on the list is the default - used when `Accept: */*` is requested
  implicit def binaryAndJsonEncoders[A <: AnyRef: Encoder]: List[EntityEncoder[F, A]] =
    List(BinaryCodec.encoder[F, A], CirceEntityEncoder.circeEntityEncoder[F, A])

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "latest" / "ordinal" =>
      import org.http4s.circe.CirceEntityCodec.circeEntityEncoder

      snapshotStorage.headSnapshot.map(_.map(_.ordinal)).flatMap {
        case Some(ordinal) => Ok(("value" ->> ordinal.value.value) :: HNil)
        case None          => NotFound()
      }

    case GET -> Root / "latest" / "metadata" =>
      import org.http4s.circe.CirceEntityCodec.circeEntityEncoder

      snapshotStorage.headSnapshot
        .flatMap(_.traverse(_.toHashed[F]))
        .map(_.map(snapshot => SnapshotMetadata(snapshot.ordinal, snapshot.hash, snapshot.lastSnapshotHash)))
        .flatMap {
          case Some(metadata) => Ok(metadata)
          case None           => NotFound()
        }

    case req @ GET -> Root / "latest" =>
      resolveEncoder[F, Signed[S]](req) { implicit enc =>
        snapshotStorage.headSnapshot.flatMap {
          case Some(snapshot) => Ok(snapshot)
          case _              => NotFound()
        }
      }

    case req @ GET -> Root / "latest" / "combined" =>
      resolveEncoder[F, (Signed[S], C)](req) { implicit enc =>
        snapshotStorage.head.flatMap {
          case Some(snapshot) => Ok(snapshot)
          case _              => NotFound()
        }
      }

    case req @ GET -> Root / SnapshotOrdinalVar(ordinal) :? FullSnapshotQueryParam(fullSnapshot) =>
      if (!fullSnapshot)
        resolveEncoder[F, Signed[S]](req) { implicit enc =>
          snapshotStorage.get(ordinal).flatMap {
            case Some(snapshot) => Ok(snapshot)
            case _              => NotFound()
          }
        }
      else
        fullGlobalSnapshotStorage.map { storage =>
          resolveEncoder[F, Signed[GlobalSnapshot]](req) { implicit enc =>
            storage.read(ordinal).flatMap {
              case Some(snapshot) => Ok(snapshot)
              case _              => NotFound()
            }
          }
        }.getOrElse(NotFound())

    case req @ GET -> Root / HashVar(hash) =>
      resolveEncoder[F, Signed[S]](req) { implicit enc =>
        snapshotStorage.get(hash).flatMap {
          case Some(snapshot) => Ok(snapshot)
          case _              => NotFound()
        }
      }

  }

  val publicRoutes: HttpRoutes[F] = Router(
    prefix -> httpRoutes
  )

  val p2pRoutes: HttpRoutes[F] = Router(
    prefix -> httpRoutes
  )
}
