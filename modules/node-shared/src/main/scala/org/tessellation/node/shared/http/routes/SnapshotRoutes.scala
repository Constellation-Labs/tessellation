package org.tessellation.node.shared.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.ext.codecs.BinaryCodec
import org.tessellation.ext.http4s.HashVar
import org.tessellation.ext.http4s.headers.negotiation.resolveEncoder
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.snapshot.storage.SnapshotStorage
import org.tessellation.node.shared.ext.http4s.SnapshotOrdinalVar
import org.tessellation.node.shared.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage
import org.tessellation.routes.internal._
import org.tessellation.schema.GlobalSnapshot
import org.tessellation.schema.snapshot.{Snapshot, SnapshotMetadata}
import org.tessellation.security.Hasher
import org.tessellation.security.signature.Signed

import io.circe.Encoder
import io.circe.shapes._
import org.http4s.circe.CirceEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.{EntityEncoder, HttpRoutes}
import shapeless.HNil
import shapeless.syntax.singleton._

final case class SnapshotRoutes[F[_]: Async: Hasher: KryoSerializer, S <: Snapshot: Encoder, C: Encoder](
  snapshotStorage: SnapshotStorage[F, S, C],
  fullGlobalSnapshotStorage: Option[SnapshotLocalFileSystemStorage[F, GlobalSnapshot]],
  prefixPath: InternalUrlPrefix
) extends Http4sDsl[F]
    with PublicRoutes[F]
    with P2PRoutes[F] {

  object FullSnapshotQueryParam extends FlagQueryParamMatcher("full")

  // first on the list is the default - used when `Accept: */*` is requested
  implicit def binaryAndJsonEncoders[A <: AnyRef: Encoder]: List[EntityEncoder[F, A]] =
    List(BinaryCodec.encoder[F, A], CirceEntityEncoder.circeEntityEncoder[F, A])

  protected val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
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

    case GET -> Root / SnapshotOrdinalVar(ordinal) / "hash" =>
      import org.http4s.circe.CirceEntityCodec.circeEntityEncoder

      snapshotStorage
        .getHash(ordinal)
        .flatMap {
          case None           => NotFound()
          case Some(snapshot) => Ok(snapshot)
        }

    case req @ GET -> Root / HashVar(hash) =>
      resolveEncoder[F, Signed[S]](req) { implicit enc =>
        snapshotStorage.get(hash).flatMap {
          case Some(snapshot) => Ok(snapshot)
          case _              => NotFound()
        }
      }

  }

  protected val public: HttpRoutes[F] = httpRoutes
  protected val p2p: HttpRoutes[F] = httpRoutes
}
