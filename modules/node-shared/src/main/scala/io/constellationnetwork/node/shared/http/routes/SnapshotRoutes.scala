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
import io.constellationnetwork.node.shared.http.p2p.middlewares.{
  ConcurrencyLimitMiddleware,
  PerIpBandwidthLimitMiddleware,
  PerIpRateLimitMiddleware
}
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
import org.http4s.headers.{ETag, `Content-Type`, `If-None-Match`}
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
  publicConcurrencyLimit: Option[HttpRoutes[F] => HttpRoutes[F]] = None,
  publicPerIpRateLimit: Option[HttpRoutes[F] => HttpRoutes[F]] = None,
  publicPerIpBandwidthLimit: Option[HttpRoutes[F] => HttpRoutes[F]] = None
) extends Http4sDsl[F]
    with PublicRoutes[F]
    with P2PRoutes[F] {

  object FullSnapshotQueryParam extends FlagQueryParamMatcher("full")

  // Use blocking encoder to prevent CPU starvation when serializing large snapshots
  implicit def jsonEncoders[A <: AnyRef: Encoder]: List[EntityEncoder[F, A]] =
    List(BlockingEntityEncoder.blockingJsonEncoder[F, A])

  private val serviceUnavailableNodeNotReady: F[Response[F]] =
    ServiceUnavailable(("message" ->> "Node is not ready yet") :: HNil)

  /** True iff the request's `If-None-Match` header indicates the client already holds the resource at `expectedTag`. Honours the RFC-7232
    * wildcard form (`If-None-Match: *`) and the multi-tag list form. Comparison is by tag value (strong validators).
    */
  private def matchesIfNoneMatch(req: Request[F], expectedTag: EntityTag): Boolean =
    req.headers.get[`If-None-Match`].exists {
      case `If-None-Match`(None)       => true // wildcard `*`
      case `If-None-Match`(Some(tags)) => tags.exists(_.tag == expectedTag.tag)
    }

  private def validStateForSnapshotReturn(state: NodeState): Boolean = state === NodeState.Ready

  private def whenNodeReady(action: F[Response[F]]): F[Response[F]] =
    nodeStorage.getNodeState
      .map(validStateForSnapshotReturn)
      .ifM(action, serviceUnavailableNodeNotReady)

  /** Fast-reject ordinals above head snapshot. Returns NotFound for ordinals beyond the current head. */
  private def rejectAboveHead(ordinal: SnapshotOrdinal)(action: F[Response[F]]): F[Response[F]] =
    snapshotStorage.headSnapshot.map(_.map(_.ordinal)).flatMap {
      case Some(head) if ordinal > head => NotFound()
      case _                            => action
    }

  /** Build the full route set. `ordinalGuard` wraps ordinal-bearing endpoints so that any new ordinal route added here automatically
    * inherits the guard. Public routes use `rejectAboveHead` to fast-reject future/pruned ordinals; p2p routes use identity (peers
    * legitimately request future ordinals during recovery observe).
    */
  private def makeRoutes(ordinalGuard: SnapshotOrdinal => F[Response[F]] => F[Response[F]]): HttpRoutes[F] =
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

        case req @ GET -> Root / "latest" / "combined" / "stream" =>
          whenNodeReady {
            // v9 (2026-04-29) ETag/304: well-behaved clients send `If-None-Match: <ordinal>` and
            // get 304 instead of a fresh 72 MB body when the chain hasn't advanced. Resolves the
            // observed pattern of external clients pulling combined-stream every 30-90s for the
            // same snapshot. The cheap `getLatestOrdinal` directory listing replaces an expensive
            // bytes-into-heap read for those cases.
            combinedSnapshotCheckpointFileSystemStorage.getLatestOrdinal.flatMap {
              case None => NotFound()
              case Some(ordinal) =>
                val expectedTag = combinedSnapshotCheckpointFileSystemStorage.etagFor(ordinal)
                if (matchesIfNoneMatch(req, expectedTag))
                  Response[F](status = Status.NotModified, headers = Headers(ETag(expectedTag))).pure[F]
                else
                  combinedSnapshotCheckpointFileSystemStorage.getAsHttpResponse(ordinal).flatMap {
                    case Some(resp) => resp.pure[F]
                    case None       => NotFound()
                  }
            }
          }

        case GET -> Root / "latest" / "combined" / "checkpoint" / "info" =>
          whenNodeReady {
            combinedSnapshotCheckpointFileSystemStorage.getLatestCheckpointInfo.flatMap(latestCheckpointInfo => Ok(latestCheckpointInfo))
          }

        case req @ GET -> Root / "latest" / "combined" / "checkpoint" / SnapshotOrdinalVar(ordinal) =>
          whenNodeReady {
            ordinalGuard(ordinal) {
              // v9 ETag/304 mirror: per-ordinal endpoint. Ordinal in the URL is itself the
              // ETag value (snapshots are immutable once finalized), so the conditional-request
              // shortcut is even more straightforward here.
              val expectedTag = combinedSnapshotCheckpointFileSystemStorage.etagFor(ordinal)
              if (matchesIfNoneMatch(req, expectedTag))
                Response[F](status = Status.NotModified, headers = Headers(ETag(expectedTag))).pure[F]
              else
                combinedSnapshotCheckpointFileSystemStorage.getAsHttpResponse(ordinal).flatMap {
                  case Some(resp) => resp.pure[F]
                  case None       => NotFound()
                }
            }
          }

        case req @ GET -> Root / SnapshotOrdinalVar(ordinal) :? FullSnapshotQueryParam(fullSnapshot) =>
          whenNodeReady {
            ordinalGuard(ordinal) {
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
            ordinalGuard(ordinal) {
              hasherSelector.withCurrent { implicit hasher =>
                snapshotStorage.getHash(ordinal)
              }.flatMap {
                case None           => NotFound()
                case Some(snapshot) => Ok(snapshot)
              }
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

  protected val public: HttpRoutes[F] = {
    val routes = makeRoutes(rejectAboveHead)
    // Composition order (innermost first):
    //   handler
    //   ──> ConcurrencyLimitMiddleware    (semaphore acquire — cheapest "is server busy?")
    //   ──> PerIpBandwidthLimitMiddleware (post-handler peek at Content-Length, pre-egress reject)
    //   ──> PerIpRateLimitMiddleware      (counter check, cheapest reject — outermost)
    //
    // v9 (2026-04-29) added the bandwidth middleware to address the apr29 observation that
    // request-rate caps (30 req/min) miss the actual cost dimension when each request is 72 MB.
    // The bandwidth middleware applies only to heavyweight snapshot routes via its `appliesTo`
    // predicate; cheap probes (`/latest/ordinal`, `/latest/metadata`) bypass it so a single
    // big fetch doesn't starve the same client's lightweight polls.
    val withConcurrency = publicConcurrencyLimit.fold(routes)(_(routes))
    val withBandwidth = publicPerIpBandwidthLimit.fold(withConcurrency)(_(withConcurrency))
    publicPerIpRateLimit.fold(withBandwidth)(_(withBandwidth))
  }
  protected val p2p: HttpRoutes[F] = makeRoutes(_ => action => action)
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
      // Only build the per-IP rate limiter when both bounds are positive — 0 disables.
      perIpRateLimit <- snapshotServingConfig
        .filter(cfg => cfg.perIpMaxRequestsPerWindow > 0 && cfg.perIpWindow.toMillis > 0)
        .traverse(cfg => PerIpRateLimitMiddleware[F](cfg.perIpMaxRequestsPerWindow, cfg.perIpWindow, cfg.perIpRetryAfterSeconds))
      // Only build the bandwidth limiter when the byte cap is positive. Restricted to heavyweight
      // routes only via the appliesTo predicate. The cheap probes (/latest/ordinal,
      // /latest/metadata, /latest/combined/checkpoint/info) MUST bypass so an IP that just
      // burned its budget on combined/stream can still ETag-check via /checkpoint/info.
      perIpBandwidthLimit <- snapshotServingConfig
        .filter(cfg => cfg.perIpMaxBytesPerWindow > 0L && cfg.perIpWindow.toMillis > 0)
        .traverse { cfg =>
          PerIpBandwidthLimitMiddleware[F](
            maxBytesPerWindow = cfg.perIpMaxBytesPerWindow,
            windowDuration = cfg.perIpWindow,
            retryAfterSeconds = cfg.perIpBandwidthRetryAfterSeconds,
            appliesTo = (req: Request[F]) => isHeavyweightSnapshotRoute(req)
          )
        }
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
        concurrencyLimit,
        perIpRateLimit,
        perIpBandwidthLimit
      )

  /** Predicate identifying heavyweight snapshot routes that PerIpBandwidthLimitMiddleware should enforce on. Scope is intentionally narrow:
    * only the routes that materialize multi-MB snapshot bodies. Lightweight metadata routes (`/latest/ordinal`, `/latest/metadata`,
    * `/latest/combined/checkpoint/info`) bypass so they remain available to a client that just burned its bandwidth budget — those are the
    * very probes a well-behaved client should use to back off.
    */
  def isHeavyweightSnapshotRoute[F[_]](req: Request[F]): Boolean = {
    val path = req.uri.path.segments.map(_.encoded).toList
    path match {
      case "latest" :: "combined" :: "stream" :: Nil                             => true
      case "latest" :: "combined" :: "checkpoint" :: ord :: Nil if ord != "info" => true
      case _                                                                     => false
    }
  }
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
