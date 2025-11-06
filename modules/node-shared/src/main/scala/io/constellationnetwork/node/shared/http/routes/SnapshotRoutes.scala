package io.constellationnetwork.node.shared.http.routes

import cats.effect._
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.ext.http4s.HashVar
import io.constellationnetwork.ext.http4s.headers.negotiation.resolveEncoder
import io.constellationnetwork.node.shared.config.types.SnapshotTimeoutsConfig
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.node.shared.ext.http4s.SnapshotOrdinalVar
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.GlobalSnapshot
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotMetadata}
import io.constellationnetwork.security.HasherSelector
import io.constellationnetwork.security.signature.Signed

import fs2.Stream
import io.circe.Encoder
import io.circe.shapes._
import io.circe.syntax._
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.circe.CirceEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.middleware.Timeout
import org.http4s.{EntityEncoder, HttpRoutes, Response}
import shapeless.HNil
import shapeless.syntax.singleton._

final case class SnapshotRoutes[F[_]: Async, S <: Snapshot: Encoder, C: Encoder](
  snapshotStorage: SnapshotStorage[F, S, C],
  fullGlobalSnapshotStorage: Option[SnapshotLocalFileSystemStorage[F, GlobalSnapshot]],
  prefixPath: InternalUrlPrefix,
  nodeStorage: NodeStorage[F],
  hasherSelector: HasherSelector[F],
  snapshotTimeoutsConfig: SnapshotTimeoutsConfig,
  limiterLatestCombined: RateLimiter[F],
  limiterLatestCombinedStream: RateLimiter[F]
) extends Http4sDsl[F]
    with PublicRoutes[F]
    with P2PRoutes[F] {

  object FullSnapshotQueryParam extends FlagQueryParamMatcher("full")

  // first on the list is the default - used when `Accept: */*` is requested
  implicit def jsonEncoders[A <: AnyRef: Encoder]: List[EntityEncoder[F, A]] = List(CirceEntityEncoder.circeEntityEncoder[F, A])

  private val serviceUnavailableNodeNotReady: F[Response[F]] =
    ServiceUnavailable(("message" ->> "Node is not ready yet") :: HNil)

  private def validStateForSnapshotReturn(state: NodeState): Boolean = state === NodeState.Ready

  protected val httpRoutes: HttpRoutes[F] = Timeout(snapshotTimeoutsConfig.routes)(HttpRoutes.of[F] {
    case GET -> Root / "latest" / "ordinal" =>
      nodeStorage.getNodeState
        .map(validStateForSnapshotReturn)
        .ifM(
          snapshotStorage.headSnapshot.map(_.map(_.ordinal)).flatMap {
            case Some(ordinal) => Ok(("value" ->> ordinal.value.value) :: HNil)
            case None          => NotFound()
          },
          serviceUnavailableNodeNotReady
        )

    case GET -> Root / "latest" / "metadata" =>
      nodeStorage.getNodeState
        .map(validStateForSnapshotReturn)
        .ifM(
          snapshotStorage.headSnapshot
            .flatMap(_.traverse(snapshot => hasherSelector.forOrdinal(snapshot.ordinal)(implicit hasher => snapshot.toHashed[F])))
            .map(_.map(snapshot => SnapshotMetadata(snapshot.ordinal, snapshot.hash, snapshot.lastSnapshotHash)))
            .flatMap {
              case Some(metadata) => Ok(metadata)
              case None           => NotFound()
            },
          serviceUnavailableNodeNotReady
        )

    case req @ GET -> Root / "latest" =>
      nodeStorage.getNodeState
        .map(validStateForSnapshotReturn)
        .ifM(
          resolveEncoder[F, Signed[S]](req) { implicit enc =>
            snapshotStorage.headSnapshot.flatMap {
              case Some(snapshot) => Ok(snapshot)
              case _              => NotFound()
            }
          },
          serviceUnavailableNodeNotReady
        )

    case req @ GET -> Root / "latest" / "combined" =>
      limiterLatestCombined.check.flatMap {
        case false =>
          TooManyRequests(("message" ->> s"Rate limit: one request every ${limiterLatestCombined.getInterval} seconds") :: HNil)
        case true =>
          nodeStorage.getNodeState
            .map(validStateForSnapshotReturn)
            .ifM(
              resolveEncoder[F, (Signed[S], C)](req) { implicit enc =>
                snapshotStorage.head.flatMap {
                  case Some(snapshot) => Ok(snapshot)
                  case _              => NotFound()
                }
              },
              serviceUnavailableNodeNotReady
            )
      }

    case GET -> Root / "latest" / "combined" / "stream" =>
      limiterLatestCombinedStream.check.flatMap {
        case false =>
          TooManyRequests(
            ("message" ->> s"Rate limit: one request every ${limiterLatestCombinedStream.getInterval} seconds") :: HNil
          )

        case true =>
          nodeStorage.getNodeState
            .map(validStateForSnapshotReturn)
            .ifM(
              snapshotStorage.head.flatMap {
                case Some(snapshot) =>
                  val json = snapshot.asJson.noSpaces
                  val stream: Stream[F, Byte] =
                    Stream.emit(json).through(fs2.text.utf8.encode)

                  Ok(stream, org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json))

                case None =>
                  NotFound()
              },
              serviceUnavailableNodeNotReady
            )
      }

    case req @ GET -> Root / SnapshotOrdinalVar(ordinal) :? FullSnapshotQueryParam(fullSnapshot) =>
      nodeStorage.getNodeState
        .map(validStateForSnapshotReturn)
        .ifM(
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
            }.getOrElse(NotFound()),
          serviceUnavailableNodeNotReady
        )

    case GET -> Root / SnapshotOrdinalVar(ordinal) / "hash" =>
      nodeStorage.getNodeState
        .map(validStateForSnapshotReturn)
        .ifM(
          hasherSelector
            .forOrdinal(ordinal) { implicit hasher =>
              snapshotStorage
                .getHash(ordinal)
            }
            .flatMap {
              case None           => NotFound()
              case Some(snapshot) => Ok(snapshot)
            },
          serviceUnavailableNodeNotReady
        )

    case req @ GET -> Root / HashVar(hash) =>
      nodeStorage.getNodeState
        .map(validStateForSnapshotReturn)
        .ifM(
          resolveEncoder[F, Signed[S]](req) { implicit enc =>
            snapshotStorage.get(hash).flatMap {
              case Some(snapshot) => Ok(snapshot)
              case _              => NotFound()
            }
          },
          serviceUnavailableNodeNotReady
        )
  })

  protected val public: HttpRoutes[F] = httpRoutes
  protected val p2p: HttpRoutes[F] = httpRoutes
}

object SnapshotRoutes {
  def make[F[_]: Async, S <: Snapshot: Encoder, C: Encoder](
    snapshotStorage: SnapshotStorage[F, S, C],
    fullGlobalSnapshotStorage: Option[SnapshotLocalFileSystemStorage[F, GlobalSnapshot]],
    prefixPath: InternalUrlPrefix,
    nodeStorage: NodeStorage[F],
    hasherSelector: HasherSelector[F],
    snapshotTimeoutsConfig: SnapshotTimeoutsConfig
  ): F[SnapshotRoutes[F, S, C]] =
    for {
      limiterLatestCombined <- RateLimiter.make[F](10.seconds)
      limiterLatestCombinedStream <- RateLimiter.make[F](1.seconds)
    } yield
      new SnapshotRoutes[F, S, C](
        snapshotStorage,
        fullGlobalSnapshotStorage,
        prefixPath,
        nodeStorage,
        hasherSelector,
        snapshotTimeoutsConfig,
        limiterLatestCombined,
        limiterLatestCombinedStream
      )
}

trait RateLimiter[F[_]] {
  def check: F[Boolean]
  def getInterval: String
}

object RateLimiter {
  def make[F[_]: Async](interval: FiniteDuration): F[RateLimiter[F]] =
    Ref[F].of(Option.empty[Long]).map { ref =>
      new RateLimiter[F] {
        def check: F[Boolean] =
          for {
            now <- Async[F].realTime.map(_.toMillis)
            allowed <- ref.modify {
              case Some(last) if now - last < interval.toMillis => (Some(last), false)
              case Some(_)                                   => (Some(now), true)
              case None                                         => (Some(now), true)
            }
          } yield allowed

        def getInterval: String = interval.toSeconds.toString
      }
    }
}
