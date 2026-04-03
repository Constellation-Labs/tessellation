package io.constellationnetwork.node.shared.http.routes

import cats.effect._
import cats.syntax.all._

import io.constellationnetwork.ext.http4s.headers.negotiation.resolveEncoder
import io.constellationnetwork.ext.http4s.{BlockingEntityEncoder, HashVar}
import io.constellationnetwork.json.StreamingCollectionEncoder
import io.constellationnetwork.node.shared.config.types.{SnapshotServingConfig, SnapshotTimeoutsConfig}
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.node.shared.ext.http4s.SnapshotOrdinalVar
import io.constellationnetwork.node.shared.http.p2p.middlewares.ConcurrencyLimitMiddleware
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{
  CombinedSnapshotCheckpointFileSystemStorage,
  SnapshotLocalFileSystemStorage
}
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, SnapshotMetadata}
import io.constellationnetwork.schema.{GlobalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security.HasherSelector
import io.constellationnetwork.security.signature.Signed

import io.circe.shapes._
import io.circe.{Encoder, Printer}
import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`
import org.http4s.server.middleware.Timeout
import shapeless.HNil
import shapeless.syntax.singleton._

final case class SnapshotRoutes[F[_]: Async, S <: Snapshot: Encoder, SI <: SnapshotInfo[_]: Encoder](
  snapshotStorage: SnapshotStorage[F, S, SI],
  fullGlobalSnapshotStorage: Option[SnapshotLocalFileSystemStorage[F, GlobalSnapshot]],
  prefixPath: InternalUrlPrefix,
  nodeStorage: NodeStorage[F],
  hasherSelector: HasherSelector[F],
  snapshotTimeoutsConfig: SnapshotTimeoutsConfig,
  cachedCombinedResponse: CachedCombinedResponse[F, S, SI],
  combinedSnapshotCheckpointFileSystemStorage: CombinedSnapshotCheckpointFileSystemStorage[F, S, SI],
  publicConcurrencyLimit: Option[HttpRoutes[F] => HttpRoutes[F]] = None
) extends Http4sDsl[F]
    with PublicRoutes[F]
    with P2PRoutes[F] {

  object FullSnapshotQueryParam extends FlagQueryParamMatcher("full")

  // Use blocking encoder to prevent CPU starvation when serializing large snapshots
  implicit def jsonEncoders[A <: AnyRef: Encoder]: List[EntityEncoder[F, A]] =
    List(BlockingEntityEncoder.blockingJsonEncoder[F, A])

  private val serviceUnavailableNodeNotReady: F[Response[F]] =
    ServiceUnavailable(("message" ->> "Node is not ready yet") :: HNil)

  private def validStateForSnapshotReturn(state: NodeState): Boolean = state === NodeState.Ready

  private def whenNodeReady(action: F[Response[F]]): F[Response[F]] =
    nodeStorage.getNodeState
      .map(validStateForSnapshotReturn)
      .ifM(action, serviceUnavailableNodeNotReady)

  /** Fast-reject ordinals above head snapshot. Applied to PUBLIC routes only — p2p peers legitimately request future ordinals during
    * recovery observe, so the p2p route set must not use this guard.
    */
  private def whenOrdinalPlausible(ordinal: SnapshotOrdinal)(action: F[Response[F]]): F[Response[F]] =
    snapshotStorage.headSnapshot.map(_.map(_.ordinal)).flatMap {
      case Some(head) if ordinal > head => NotFound()
      case _                            => action
    }

  /** Core routes shared by both public and p2p. Does NOT include the ordinal plausibility guard. */
  private def coreRoutes: HttpRoutes[F] =
    Timeout(snapshotTimeoutsConfig.routes)(
      HttpRoutes.of[F] {
        case GET -> Root / "latest" / "ordinal" =>
          whenNodeReady {
            snapshotStorage.headSnapshot.map(_.map(_.ordinal)).flatMap {
              case Some(ordinal) => Ok(("value" ->> ordinal.value.value) :: HNil)
              case None          => NotFound()
            }
          }

        case GET -> Root / "latest" / "metadata" =>
          whenNodeReady {
            snapshotStorage.headSnapshot
              .flatMap(_.traverse(snapshot => hasherSelector.withCurrent(implicit hasher => snapshot.toHashed[F])))
              .map(_.map(snapshot => SnapshotMetadata(snapshot.ordinal, snapshot.hash, snapshot.lastSnapshotHash)))
              .flatMap {
                case Some(metadata) => Ok(metadata)
                case None           => NotFound()
              }
          }

        case req @ GET -> Root / "latest" =>
          whenNodeReady {
            resolveEncoder[F, Signed[S]](req) { implicit enc =>
              snapshotStorage.headSnapshot.flatMap {
                case Some(snapshot) => Ok(snapshot)
                case _              => NotFound()
              }
            }
          }

        case GET -> Root / "latest" / "combined" =>
          whenNodeReady {
            snapshotStorage.head.flatMap {
              case Some((snapshot, state)) =>
                cachedCombinedResponse.get(snapshot.ordinal, snapshot, state).flatMap { bytes =>
                  Ok(
                    fs2.Stream.chunk[F, Byte](fs2.Chunk.array(bytes)),
                    `Content-Type`(MediaType.application.json)
                  )
                }
              case _ => NotFound()
            }
          }

        case GET -> Root / "latest" / "combined" / "stream" =>
          whenNodeReady {
            combinedSnapshotCheckpointFileSystemStorage.getLatestAsHttpResponse.flatMap {
              case Some(resp) => resp.pure[F]
              case None       => NotFound()
            }
          }

        case GET -> Root / "latest" / "combined" / "checkpoint" / "info" =>
          whenNodeReady {
            combinedSnapshotCheckpointFileSystemStorage.getLatestCheckpointInfo.flatMap(latestCheckpointInfo => Ok(latestCheckpointInfo))
          }

        case GET -> Root / "latest" / "combined" / "checkpoint" / SnapshotOrdinalVar(ordinal) =>
          whenNodeReady {
            combinedSnapshotCheckpointFileSystemStorage
              .getAsStream(ordinal)
              .flatMap {
                case Some(byteStream) =>
                  Ok(byteStream, org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json))
                case None => NotFound()
              }
          }

        case req @ GET -> Root / SnapshotOrdinalVar(ordinal) :? FullSnapshotQueryParam(fullSnapshot) =>
          whenNodeReady {
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
          }

        case GET -> Root / SnapshotOrdinalVar(ordinal) / "hash" =>
          whenNodeReady {
            hasherSelector.withCurrent { implicit hasher =>
              snapshotStorage.getHash(ordinal)
            }.flatMap {
              case None           => NotFound()
              case Some(snapshot) => Ok(snapshot)
            }
          }

        case req @ GET -> Root / HashVar(hash) =>
          whenNodeReady {
            resolveEncoder[F, Signed[S]](req) { implicit enc =>
              snapshotStorage.get(hash).flatMap {
                case Some(snapshot) => Ok(snapshot)
                case _              => NotFound()
              }
            }
          }
      }
    )

  /** Public routes: core routes enhanced with ordinal plausibility guard on ordinal-based endpoints. This fast-rejects requests for
    * future/pruned ordinals without filesystem overhead.
    */
  private val publicRoutes: HttpRoutes[F] =
    Timeout(snapshotTimeoutsConfig.routes)(
      HttpRoutes.of[F] {
        case GET -> Root / "latest" / "combined" / "checkpoint" / SnapshotOrdinalVar(ordinal) =>
          whenNodeReady {
            whenOrdinalPlausible(ordinal) {
              combinedSnapshotCheckpointFileSystemStorage
                .getAsStream(ordinal)
                .flatMap {
                  case Some(byteStream) =>
                    Ok(byteStream, org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json))
                  case None => NotFound()
                }
            }
          }

        case req @ GET -> Root / SnapshotOrdinalVar(ordinal) :? FullSnapshotQueryParam(fullSnapshot) =>
          whenNodeReady {
            whenOrdinalPlausible(ordinal) {
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
            }
          }

        case GET -> Root / SnapshotOrdinalVar(ordinal) / "hash" =>
          whenNodeReady {
            whenOrdinalPlausible(ordinal) {
              hasherSelector.withCurrent { implicit hasher =>
                snapshotStorage.getHash(ordinal)
              }.flatMap {
                case None           => NotFound()
                case Some(snapshot) => Ok(snapshot)
              }
            }
          }
      }
    )

  protected val public: HttpRoutes[F] = {
    // Public: ordinal-guarded routes take priority, then fall through to core routes
    val combined = publicRoutes <+> coreRoutes
    publicConcurrencyLimit.fold(combined)(_(combined))
  }
  protected val p2p: HttpRoutes[F] = coreRoutes
}

object SnapshotRoutes {
  def make[F[_]: Async, S <: Snapshot: Encoder, SI <: SnapshotInfo[_]: Encoder](
    snapshotStorage: SnapshotStorage[F, S, SI],
    fullGlobalSnapshotStorage: Option[SnapshotLocalFileSystemStorage[F, GlobalSnapshot]],
    prefixPath: InternalUrlPrefix,
    nodeStorage: NodeStorage[F],
    hasherSelector: HasherSelector[F],
    snapshotTimeoutsConfig: SnapshotTimeoutsConfig,
    combinedSnapshotCheckpointFileSystemStorage: CombinedSnapshotCheckpointFileSystemStorage[F, S, SI],
    snapshotServingConfig: Option[SnapshotServingConfig] = None
  ): F[SnapshotRoutes[F, S, SI]] =
    for {
      cachedCombined <- CachedCombinedResponse.make[F, S, SI]
      concurrencyLimit <- snapshotServingConfig.traverse(cfg =>
        ConcurrencyLimitMiddleware[F](cfg.maxConcurrentPublic, cfg.retryAfterSeconds)
      )
    } yield
      new SnapshotRoutes[F, S, SI](
        snapshotStorage,
        fullGlobalSnapshotStorage,
        prefixPath,
        nodeStorage,
        hasherSelector,
        snapshotTimeoutsConfig,
        cachedCombined,
        combinedSnapshotCheckpointFileSystemStorage,
        concurrencyLimit
      )
}

trait CachedCombinedResponse[F[_], S <: Snapshot, SI <: SnapshotInfo[_]] {
  def get(currentOrdinal: SnapshotOrdinal, snapshot: Signed[S], state: SI): F[Array[Byte]]
}

object CachedCombinedResponse {
  private val printer: Printer = Printer.noSpaces.copy(dropNullValues = true)

  def make[F[_]: Async, S <: Snapshot: Encoder, SI <: SnapshotInfo[_]: Encoder]: F[CachedCombinedResponse[F, S, SI]] =
    Ref[F].of(Option.empty[(SnapshotOrdinal, Deferred[F, Either[Throwable, Array[Byte]]])]).map { ref =>
      new CachedCombinedResponse[F, S, SI] {
        private def serialize(snapshot: Signed[S], state: SI): F[Array[Byte]] =
          Async[F].blocking {
            val baos = new java.io.ByteArrayOutputStream()
            val writer = new java.io.OutputStreamWriter(baos, "UTF-8")

            writer.append('[')
            Encoder[Signed[S]] match {
              case sce: StreamingCollectionEncoder[Signed[S]] =>
                sce.streamEncode(snapshot, printer, writer)
              case enc =>
                printer.unsafePrintToAppendable(enc(snapshot), writer)
            }
            writer.append(',')
            Encoder[SI] match {
              case sce: StreamingCollectionEncoder[SI] =>
                sce.streamEncode(state, printer, writer)
              case enc =>
                printer.unsafePrintToAppendable(enc(state), writer)
            }
            writer.append(']')
            writer.flush()
            baos.toByteArray
          }

        def get(currentOrdinal: SnapshotOrdinal, snapshot: Signed[S], state: SI): F[Array[Byte]] =
          ref.get.flatMap {
            case Some((ord, existing)) if ord === currentOrdinal =>
              existing.get.flatMap(Async[F].fromEither)
            case _ =>
              Deferred[F, Either[Throwable, Array[Byte]]].flatMap { newDef =>
                ref.modify {
                  case Some((ord, existing)) if ord === currentOrdinal =>
                    (Some((ord, existing)), existing.get.flatMap(Async[F].fromEither))
                  case _ =>
                    (
                      Some((currentOrdinal, newDef)),
                      serialize(snapshot, state).attempt.flatTap(newDef.complete).flatMap(Async[F].fromEither)
                    )
                }.flatten
              }
          }
      }
    }
}
