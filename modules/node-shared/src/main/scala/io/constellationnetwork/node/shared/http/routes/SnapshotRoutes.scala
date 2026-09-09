package io.constellationnetwork.node.shared.http.routes

import cats.effect._
import cats.effect.std.Semaphore
import cats.syntax.all._

import io.constellationnetwork.ext.http4s.headers.negotiation.resolveEncoder
import io.constellationnetwork.ext.http4s.{BlockingEntityEncoder, HashVar}
import io.constellationnetwork.json.StreamingCollectionEncoder
import io.constellationnetwork.node.shared.config.types.{SnapshotServingConfig, SnapshotTimeoutsConfig}
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.ext.http4s.SnapshotOrdinalVar
import io.constellationnetwork.node.shared.http.p2p.headers.`X-Id`
import io.constellationnetwork.node.shared.http.p2p.middlewares.{
  ConcurrencyLimitMiddleware,
  PerIpBandwidthLimitMiddleware,
  PerIpRateLimitMiddleware
}
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
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

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import io.circe.shapes._
import io.circe.{Encoder, Printer}
import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.headers._
import org.http4s.server.middleware.Timeout
import org.typelevel.log4cats.slf4j.Slf4jLogger
import shapeless.HNil
import shapeless.syntax.singleton._

final case class SnapshotRoutes[F[_]: Async: Metrics, S <: Snapshot: Encoder, SI <: SnapshotInfo[_]: Encoder](
  snapshotStorage: SnapshotStorage[F, S, SI],
  // On WaitingForReady peers reached via the download path, snapshotStorage.head returns None
  // (it's only populated as the node produces snapshots). lastNSnapshotStorage IS populated for
  // such peers via Download.recoveryObserve setForRecovery. When provided, we use it as a fallback
  // so the /latest endpoints serve correctly from WaitingForReady peers - critical for parallel
  // snapshot download distribution after rollback. None for layers (e.g. currency-l0) that don't
  // have an equivalent LastN storage; those preserve the legacy behavior.
  lastNSnapshotStorage: Option[LastSnapshotStorage[F, S, SI]],
  fullGlobalSnapshotStorage: Option[SnapshotLocalFileSystemStorage[F, GlobalSnapshot]],
  prefixPath: InternalUrlPrefix,
  nodeStorage: NodeStorage[F],
  hasherSelector: HasherSelector[F],
  snapshotTimeoutsConfig: SnapshotTimeoutsConfig,
  cachedCombinedResponse: CachedCombinedResponse[F, S, SI],
  combinedSnapshotCheckpointFileSystemStorage: CombinedSnapshotCheckpointFileSystemStorage[F, S, SI],
  // Route-scoped concurrency cap for heavy snapshot serves. Layered INSIDE the public
  // middleware chain so it applies regardless of whether the request is anonymous or
  // peer-authenticated. Held only for the heavy handlers (`/latest/combined/stream` and
  // `/{ordinal}?full=true`); cheap probe routes are unbounded by this cap.
  heavyRouteConcurrency: Semaphore[F],
  heavyRouteActiveRef: Ref[F, Map[String, Int]],
  // Retry-After value (seconds) returned with 503 responses when `heavyRouteConcurrency`
  // is saturated. Sourced from `SnapshotServingConfig.retryAfterSeconds` for parity with
  // the existing global cap response shape.
  heavyRouteRetryAfterSeconds: Long,
  publicConcurrencyLimit: Option[HttpRoutes[F] => HttpRoutes[F]] = None,
  publicPerIpRateLimit: Option[HttpRoutes[F] => HttpRoutes[F]] = None,
  publicPerIpBandwidthLimit: Option[HttpRoutes[F] => HttpRoutes[F]] = None
) extends Http4sDsl[F]
    with PublicRoutes[F]
    with P2PRoutes[F] {

  object FullSnapshotQueryParam extends FlagQueryParamMatcher("full")

  private val logger = Slf4jLogger.getLogger[F]

  private val endpointLabel = Metrics.unsafeLabelName("endpoint")
  private val outcomeLabel = Metrics.unsafeLabelName("outcome")
  private val limiterLabel = Metrics.unsafeLabelName("limiter")
  private val callerLabel = Metrics.unsafeLabelName("caller")

  private def snapshotStreamTags(endpoint: String, outcome: String, caller: String): Metrics.TagSeq =
    Seq(endpointLabel -> endpoint, outcomeLabel -> outcome, callerLabel -> caller)

  private def snapshotStreamLimitTags(endpoint: String, limiter: String, caller: String): Metrics.TagSeq =
    Seq(endpointLabel -> endpoint, limiterLabel -> limiter, outcomeLabel -> "throttled", callerLabel -> caller)

  private def requestClientIp(req: Request[F]): String =
    req.headers
      .get[`X-Forwarded-For`]
      .flatMap(_.values.head)
      .map(_.toString.split(",").head.trim)
      .filter(_.nonEmpty)
      .orElse(req.remote.map(_.host.toString))
      .getOrElse("unknown")

  private def snapshotStreamCaller(req: Request[F]): String =
    req.headers.get[`X-Id`].fold("external")(_ => "peer")

  private def classifySnapshotStreamOutcome(status: Status): String =
    status match {
      case Status.NotModified                 => "not_modified"
      case Status.NotFound                    => "not_found"
      case Status.TooManyRequests             => "throttled"
      case Status.ServiceUnavailable          => "unavailable"
      case s if s.code >= 200 && s.code < 300 => "served"
      case s if s.code >= 400 && s.code < 500 => "client_error"
      case s if s.code >= 500                 => "error"
      case _                                  => "other"
    }

  private def updateSnapshotStreamActive(endpoint: String, delta: Int): F[Unit] =
    heavyRouteActiveRef.modify { current =>
      val next = math.max(0, current.getOrElse(endpoint, 0) + delta)
      (current.updated(endpoint, next), next)
    }.flatMap { active =>
      Metrics[F].updateGauge("dag_snapshot_stream_active", active, Seq(endpointLabel -> endpoint))
    }

  private def observeSnapshotStreamLimit(req: Request[F], limiter: String, observed: Long, cap: Long): F[Unit] =
    SnapshotRoutes.heavyweightEndpoint(req).traverse_ { endpoint =>
      Metrics[F]
        .incrementCounter("dag_snapshot_stream_limit_total", snapshotStreamLimitTags(endpoint, limiter, snapshotStreamCaller(req))) >>
        logger.debug(
          s"Snapshot stream $limiter limit rejected endpoint=$endpoint ip=${requestClientIp(req)} observed=$observed cap=$cap"
        )
    }

  /** Records the lifetime of heavyweight snapshot streams without labeling Prometheus by IP.
    *
    * The generic HTTP middleware records dispatch latency, but these routes spend most of their cost streaming the response body. The
    * finalizer observes the body lifetime, including slow consumers. fs2 finalization here is intentionally low-cardinality; client IP is
    * reserved for bounded debug/error logs only.
    */
  private def withSnapshotStreamObservability(
    req: Request[F],
    endpoint: String
  )(action: F[Response[F]]): F[Response[F]] =
    for {
      start <- Async[F].realTime
      _ <- updateSnapshotStreamActive(endpoint, 1)
      response <- action.handleErrorWith { err =>
        Async[F].realTime.flatMap { end =>
          val duration = end - start
          val tags = snapshotStreamTags(endpoint, "error", snapshotStreamCaller(req))
          updateSnapshotStreamActive(endpoint, -1) >>
            Metrics[F].incrementCounter("dag_snapshot_stream_request_total", tags) >>
            Metrics[F].recordTimeHistogram("dag_snapshot_stream", duration, tags) >>
            logger.warn(err)(s"Snapshot stream handler failed endpoint=$endpoint ip=${requestClientIp(req)}") >>
            Async[F].raiseError[Response[F]](err)
        }
      }
      outcome = classifySnapshotStreamOutcome(response.status)
      tags = snapshotStreamTags(endpoint, outcome, snapshotStreamCaller(req))
      bytes = response.contentLength
      finalize = Async[F].realTime.flatMap { end =>
        val duration = end - start
        updateSnapshotStreamActive(endpoint, -1) >>
          Metrics[F].incrementCounter("dag_snapshot_stream_request_total", tags) >>
          Metrics[F].recordTimeHistogram("dag_snapshot_stream", duration, tags) >>
          bytes.traverse_ { size =>
            Metrics[F].incrementCounterBy("dag_snapshot_stream_bytes_total", size, tags) >>
              Metrics[F].recordSizeHistogram("dag_snapshot_stream_response", size, tags)
          } >>
          (response.status.code >= 500)
            .pure[F]
            .ifM(
              logger.warn(
                s"Snapshot stream completed with server error endpoint=$endpoint status=${response.status.code} ip=${requestClientIp(req)} bytes=${bytes
                    .getOrElse(0L)} durationMs=${duration.toMillis}"
              ),
              Async[F].unit
            )
      }
    } yield response.copy(body = response.body.onFinalizeWeak(finalize))

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

  // WaitingForReady peers have run initFromDownload successfully and have a consensus-validated head snapshot
  // loaded in storage. The bytes they serve for historical snapshot reads are equivalent to a Ready peer's.
  // Allowing them to serve unblocks the post-rollback bottleneck where joining peers funnel through the
  // lone Ready node because sibling source nodes await a round to close before transitioning to Ready.
  private def validStateForSnapshotReturn(state: NodeState): Boolean =
    state === NodeState.Ready || state === NodeState.WaitingForReady

  // Fallback helpers: snapshotStorage.head is populated as the node PRODUCES snapshots
  // (consensus head). On a WaitingForReady peer reached via the download path, the
  // production head is empty - but lastNSnapshotStorage is populated via Download.recoveryObserve
  // setForRecovery. Falling back keeps the /latest endpoints serving correctly without
  // changing semantics on Ready peers (snapshotStorage.head is the canonical source there).
  private def headSnapshotWithFallback: F[Option[Signed[S]]] =
    snapshotStorage.headSnapshot.flatMap {
      case s @ Some(_) => (s: Option[Signed[S]]).pure[F]
      case None =>
        lastNSnapshotStorage.fold(Option.empty[Signed[S]].pure[F])(_.get.map(_.map(_.signed)))
    }

  private def headWithFallback: F[Option[(Signed[S], SI)]] =
    snapshotStorage.head.flatMap {
      case s @ Some(_) => (s: Option[(Signed[S], SI)]).pure[F]
      case None =>
        lastNSnapshotStorage.fold(Option.empty[(Signed[S], SI)].pure[F])(
          _.getCombined.map(_.map { case (h, si) => (h.signed, si) })
        )
    }

  private def whenNodeReady(action: F[Response[F]]): F[Response[F]] =
    nodeStorage.getNodeState
      .map(validStateForSnapshotReturn)
      .ifM(action, serviceUnavailableNodeNotReady)

  /** Route-scoped heavy-serve cap. Tries to acquire a permit on `heavyRouteConcurrency`; on saturation, returns 503 with a Retry-After
    * header without running `action`. On acquisition, the permit is attached to the response body's stream finalizer so it is released only
    * after the stream terminates (success, error, or cancellation). This ties permit lifetime to slow consumer drain, which is what we want
    * for bounding total in-flight serves rather than just dispatch.
    *
    * If `action` itself fails before producing a response, the permit is released synchronously via `handleErrorWith` so the cap doesn't
    * leak permits on handler errors.
    *
    * Layered INSIDE the public middleware chain (ConcurrencyLimitMiddleware / PerIpBandwidthLimitMiddleware / PerIpRateLimitMiddleware), so
    * it applies whether the request is anonymous or peer-authenticated. Cheap probe routes bypass this guard entirely.
    */
  private def withHeavyRoutePermit(req: Request[F], endpoint: String)(action: F[Response[F]]): F[Response[F]] = {
    val release = heavyRouteConcurrency.release
    heavyRouteConcurrency.tryAcquire.flatMap {
      case false =>
        observeSnapshotStreamLimit(req, "concurrency", 1L, 1L) >>
          Response[F](status = Status.ServiceUnavailable)
            .putHeaders(`Retry-After`.unsafeFromLong(heavyRouteRetryAfterSeconds))
            .pure[F]
      case true =>
        withSnapshotStreamObservability(req, endpoint)(action)
          .map(resp => resp.copy(body = resp.body.onFinalizeWeak(release)))
          .handleErrorWith(t => release >> Async[F].raiseError[Response[F]](t))
    }
  }

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
            headSnapshotWithFallback.map(_.map(_.ordinal)).flatMap {
              case Some(ordinal) => Ok(("value" ->> ordinal.value.value) :: HNil)
              case None          => NotFound()
            }
          }

        case GET -> Root / "latest" / "metadata" =>
          whenNodeReady {
            headSnapshotWithFallback
              .flatMap(_.traverse(snapshot => hasherSelector.withCurrent(implicit hasher => snapshot.toHashed[F])))
              .map(
                _.map(snapshot =>
                  // Include epochProgress so the L1 alignment loop and other
                  // cheap consumers can read it without pulling the ~60 MB combined-snapshot body.
                  SnapshotMetadata(
                    snapshot.ordinal,
                    snapshot.hash,
                    snapshot.lastSnapshotHash,
                    Some(snapshot.signed.value.epochProgress)
                  )
                )
              )
              .flatMap {
                case Some(metadata) => Ok(metadata)
                case None           => NotFound()
              }
          }

        case req @ GET -> Root / "latest" =>
          whenNodeReady {
            resolveEncoder[F, Signed[S]](req) { implicit enc =>
              headSnapshotWithFallback.flatMap {
                case Some(snapshot) => Ok(snapshot)
                case _              => NotFound()
              }
            }
          }

        case req @ GET -> Root / "latest" / "combined" =>
          whenNodeReady {
            withHeavyRoutePermit(req, "latest_combined") {
              headWithFallback.flatMap {
                case Some((snapshot, state)) =>
                  cachedCombinedResponse.get(snapshot.ordinal, snapshot, state).flatMap { bytes =>
                    Response[F](status = Status.Ok)
                      .withEntity(bytes)(EntityEncoder.byteArrayEncoder[F])
                      .putHeaders(`Content-Type`(MediaType.application.json))
                      .pure[F]
                  }
                case _ => NotFound()
              }
            }
          }

        case req @ GET -> Root / "latest" / "combined" / "stream" =>
          whenNodeReady {
            withHeavyRoutePermit(req, "latest_combined_stream") {
              // ETag/304 + correctness fix: the strong validator now encodes
              // the full immutable identity `(ordinal, snapshotHash)`. Ordinal alone is insufficient
              // -- ord-N can carry different bytes on different forks, so a stale-(N, H1) cache
              // claiming `If-None-Match: "N"` against the canonical (N, H2) would falsely 304.
              // Including the hash makes the 304 path correct under fork-recovery.
              //
              // "Anything stored?" comes from `getLatestOrdinal` (a directory listing on disk). Using
              // the in-memory `getLatestCheckpointInfo` Ref instead would 404 on cold-restart until
              // the first new write -- that Ref is reset to `empty()` on startup and only repopulated
              // by `tryWrite`. The hash cache is allowed to miss; we just skip ETag emission in that
              // case rather than 404. This matches the per-ord checkpoint route's behavior below.
              combinedSnapshotCheckpointFileSystemStorage.getLatestOrdinal.flatMap {
                case None => NotFound()
                case Some(ordinal) =>
                  combinedSnapshotCheckpointFileSystemStorage.getCachedHash(ordinal).flatMap {
                    case Some(hash) =>
                      val expectedTag = combinedSnapshotCheckpointFileSystemStorage.etagFor(ordinal, hash)
                      if (matchesIfNoneMatch(req, expectedTag))
                        Response[F](status = Status.NotModified, headers = Headers(ETag(expectedTag))).pure[F]
                      else
                        combinedSnapshotCheckpointFileSystemStorage.getAsHttpResponse(ordinal).flatMap {
                          case Some(resp) => resp.pure[F]
                          case None       => NotFound()
                        }
                    case None =>
                      // Cold-restart historical: hash unknown, skip the conditional check and serve
                      // the body. Strictly correct, just no optimization for this single request.
                      combinedSnapshotCheckpointFileSystemStorage.getAsHttpResponse(ordinal).flatMap {
                        case Some(resp) => resp.pure[F]
                        case None       => NotFound()
                      }
                  }
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
              withSnapshotStreamObservability(req, "combined_checkpoint") {
                // Per-ordinal ETag now encodes (ordinal, snapshotHash) for the
                // same fork-correctness reason as the latest-stream variant above. We can only
                // emit the ETag when the hash is in our cache (populated at write time, retained
                // via the storage's bounded-size hashCache). Cold-restart historical reads with
                // no cached hash bypass the conditional check entirely -- strictly correct, just
                // no optimization for that single request.
                combinedSnapshotCheckpointFileSystemStorage.getCachedHash(ordinal).flatMap {
                  case Some(hash) =>
                    val expectedTag = combinedSnapshotCheckpointFileSystemStorage.etagFor(ordinal, hash)
                    if (matchesIfNoneMatch(req, expectedTag))
                      Response[F](status = Status.NotModified, headers = Headers(ETag(expectedTag))).pure[F]
                    else
                      combinedSnapshotCheckpointFileSystemStorage.getAsHttpResponse(ordinal).flatMap {
                        case Some(resp) => resp.pure[F]
                        case None       => NotFound()
                      }
                  case None =>
                    combinedSnapshotCheckpointFileSystemStorage.getAsHttpResponse(ordinal).flatMap {
                      case Some(resp) => resp.pure[F]
                      case None       => NotFound()
                    }
                }
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
                withHeavyRoutePermit(req, "full_snapshot") {
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
    // The bandwidth middleware was added to address the observation that
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
  private val endpointLabel = Metrics.unsafeLabelName("endpoint")
  private val outcomeLabel = Metrics.unsafeLabelName("outcome")
  private val limiterLabel = Metrics.unsafeLabelName("limiter")
  private val callerLabel = Metrics.unsafeLabelName("caller")

  private def snapshotStreamCaller[F[_]](req: Request[F]): String =
    req.headers.get[`X-Id`].fold("external")(_ => "peer")

  private def snapshotStreamLimitTags(endpoint: String, limiter: String, caller: String): Metrics.TagSeq =
    Seq(endpointLabel -> endpoint, limiterLabel -> limiter, outcomeLabel -> "throttled", callerLabel -> caller)

  def make[F[_]: Async: Metrics, S <: Snapshot: Encoder, SI <: SnapshotInfo[_]: Encoder](
    snapshotStorage: SnapshotStorage[F, S, SI],
    lastNSnapshotStorage: Option[LastSnapshotStorage[F, S, SI]],
    fullGlobalSnapshotStorage: Option[SnapshotLocalFileSystemStorage[F, GlobalSnapshot]],
    prefixPath: InternalUrlPrefix,
    nodeStorage: NodeStorage[F],
    hasherSelector: HasherSelector[F],
    snapshotTimeoutsConfig: SnapshotTimeoutsConfig,
    combinedSnapshotCheckpointFileSystemStorage: CombinedSnapshotCheckpointFileSystemStorage[F, S, SI],
    snapshotServingConfig: Option[SnapshotServingConfig] = None,
    // The local node's external IP. Plumbed into the per-IP rate/bandwidth limiters so they can
    // detect the XFF-self-injection case (LB injecting our own IP into X-Forwarded-For) and fall
    // back to the TCP remote. Without this guard, all LB-injected requests share a single counter
    // under our own IP, which on bootstrap-source nodes saturates within seconds and starts 429ing
    // healthcheck probes -- observed on testnet .193.
    selfExternalIp: Option[String] = None
  ): F[SnapshotRoutes[F, S, SI]] =
    for {
      cachedCombined <- CachedCombinedResponse.make[F, S, SI]
      concurrencyLimit <- snapshotServingConfig.traverse(cfg =>
        ConcurrencyLimitMiddleware[F](cfg.maxConcurrentPublic, cfg.retryAfterSeconds)
      )
      // Only build the per-IP rate limiter when both bounds are positive: 0 disables.
      perIpRateLimit <- snapshotServingConfig
        .filter(cfg => cfg.perIpMaxRequestsPerWindow > 0 && cfg.perIpWindow.toMillis > 0)
        .traverse(cfg =>
          PerIpRateLimitMiddleware[F](
            cfg.perIpMaxRequestsPerWindow,
            cfg.perIpWindow,
            cfg.perIpRetryAfterSeconds,
            cfg.perIpAllowlist.split(",").iterator.map(_.trim).filter(_.nonEmpty).toSet,
            selfExternalIp,
            onReject = Some { (req, _, _, _) =>
              heavyweightEndpoint(req).traverse_ { endpoint =>
                Metrics[F].incrementCounter(
                  "dag_snapshot_stream_limit_total",
                  snapshotStreamLimitTags(endpoint, "request", snapshotStreamCaller(req))
                )
              }
            }
          )
        )
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
            maxBytesPerLongWindow = cfg.perIpMaxBytesPerLongWindow,
            longWindowDuration = cfg.perIpLongWindow,
            maxBytesPerAggregateLongWindow = cfg.maxBytesPerLongWindow,
            aggregateLongWindowDuration = cfg.longWindow,
            retryAfterSeconds = cfg.perIpBandwidthRetryAfterSeconds,
            adaptiveBackoffEnabled = cfg.adaptiveBackoffEnabled,
            adaptiveBackoffMaxRequestsPerWindow = cfg.adaptiveBackoffMaxRequestsPerWindow,
            adaptiveBackoffMaxBytesPerWindow = cfg.adaptiveBackoffMaxBytesPerWindow,
            adaptiveBackoffWindowDuration = cfg.adaptiveBackoffWindow,
            adaptiveBackoffBaseRetryAfterSeconds = cfg.adaptiveBackoffBaseRetryAfterSeconds,
            adaptiveBackoffMaxRetryAfterSeconds = cfg.adaptiveBackoffMaxRetryAfterSeconds,
            adaptiveBackoffPenaltyDecay = cfg.adaptiveBackoffPenaltyDecay,
            adaptiveBackoffApplyToAllowlist = cfg.adaptiveBackoffApplyToAllowlist,
            appliesTo = (req: Request[F]) => isHeavyweightSnapshotRoute(req),
            allowlist = cfg.perIpAllowlist.split(",").iterator.map(_.trim).filter(_.nonEmpty).toSet,
            selfExternalIp = selfExternalIp,
            // Pre-flight reject: estimator returns the on-disk checkpoint size for the heavy
            // combined-stream routes so the limiter can reject 100MB requests BEFORE the route
            // handler builds the response body. Other routes get `None` and fall through to
            // the legacy post-response Content-Length path (defense in depth).
            routeSizeEstimator = Some(combinedStreamRouteSizeEstimator[F, S, SI](combinedSnapshotCheckpointFileSystemStorage)),
            onReject = Some { (req, scope, _, _) =>
              val limiter =
                if (scope === "aggregate") "aggregate_bandwidth"
                else if (scope.startsWith("adaptive_")) "adaptive_backoff"
                else "bandwidth"
              heavyweightEndpoint(req).traverse_ { endpoint =>
                Metrics[F].incrementCounter(
                  "dag_snapshot_stream_limit_total",
                  snapshotStreamLimitTags(endpoint, limiter, snapshotStreamCaller(req))
                )
              }
            }
          )
        }
      heavyRouteCapacity: PosInt = snapshotServingConfig.map(_.heavyRouteConcurrency).getOrElse(PosInt(6))
      heavyRouteRetryAfter: Long = snapshotServingConfig.map(_.retryAfterSeconds).getOrElse(2L)
      heavyRoutePermit <- Semaphore[F](heavyRouteCapacity.value.toLong)
      heavyRouteActiveRef <- Ref.of[F, Map[String, Int]](Map.empty)
    } yield
      new SnapshotRoutes[F, S, SI](
        snapshotStorage,
        lastNSnapshotStorage,
        fullGlobalSnapshotStorage,
        prefixPath,
        nodeStorage,
        hasherSelector,
        snapshotTimeoutsConfig,
        cachedCombined,
        combinedSnapshotCheckpointFileSystemStorage,
        heavyRoutePermit,
        heavyRouteActiveRef,
        heavyRouteRetryAfter,
        concurrencyLimit,
        perIpRateLimit,
        perIpBandwidthLimit
      )

  def heavyweightEndpoint[F[_]](req: Request[F]): Option[String] = {
    val path = req.uri.path.segments.map(_.encoded).toList
    path match {
      case "latest" :: "combined" :: Nil                                         => Some("latest_combined")
      case "latest" :: "combined" :: "stream" :: Nil                             => Some("latest_combined_stream")
      case "latest" :: "combined" :: "checkpoint" :: ord :: Nil if ord != "info" => Some("combined_checkpoint")
      case ord :: Nil if ord.toLongOption.flatMap(SnapshotOrdinal(_)).isDefined && req.params.contains("full") =>
        Some("full_snapshot")
      case _ => None
    }
  }

  /** Predicate identifying heavyweight snapshot routes that PerIpBandwidthLimitMiddleware should enforce on. Scope is intentionally narrow:
    * only the routes that materialize multi-MB snapshot bodies. Lightweight metadata routes (`/latest/ordinal`, `/latest/metadata`,
    * `/latest/combined/checkpoint/info`) bypass so they remain available to a client that just burned its bandwidth budget - those are the
    * very probes a well-behaved client should use to back off.
    */
  def isHeavyweightSnapshotRoute[F[_]](req: Request[F]): Boolean =
    heavyweightEndpoint(req).exists(endpoint =>
      endpoint === "latest_combined" || endpoint === "latest_combined_stream" || endpoint === "combined_checkpoint"
    )

  /** Pre-flight size estimator for the per-IP bandwidth limiter. Maps the combined-snapshot routes to their on-disk byte size so the
    * limiter can refuse over-budget requests BEFORE the heavy route handler builds the ~100 MB response. Returns `None` for any other route
    * (including the lightweight probes) so they fall through to the legacy post-response Content-Length accounting.
    *
    * Resolution:
    *   - `/latest/combined`: resolve latest ordinal via the checkpoint storage, then ask for its on-disk size. The in-memory cached route
    *     should match that checkpoint size, and the strict byte-array entity advertises its actual length for post-check defense in depth.
    *   - `/latest/combined/stream`: resolve latest ordinal via the checkpoint storage, then ask for its on-disk size.
    *   - `/latest/combined/checkpoint/{ord}`: parse the ordinal from the path, look up its on-disk size.
    *
    * The estimator is best-effort: when the checkpoint is absent (e.g. cold-start before first write) it returns `None` and the limiter
    * falls back to running the route. The route will 404 in that case, which the post-response Content-Length check accounts as 0 bytes -
    * the right outcome for a missing checkpoint.
    */
  def combinedStreamRouteSizeEstimator[F[_]: Async, S <: Snapshot, SI <: SnapshotInfo[_]](
    storage: CombinedSnapshotCheckpointFileSystemStorage[F, S, SI]
  ): Request[F] => F[Option[Long]] = { req =>
    val path = req.uri.path.segments.map(_.encoded).toList
    path match {
      case "latest" :: "combined" :: Nil =>
        storage.getLatestOrdinal.flatMap {
          case Some(ord) => storage.getCheckpointSize(ord)
          case None      => Option.empty[Long].pure[F]
        }
      case "latest" :: "combined" :: "stream" :: Nil =>
        storage.getLatestOrdinal.flatMap {
          case Some(ord) => storage.getCheckpointSize(ord)
          case None      => Option.empty[Long].pure[F]
        }
      case "latest" :: "combined" :: "checkpoint" :: ord :: Nil if ord != "info" =>
        ord.toLongOption.flatMap(SnapshotOrdinal(_)) match {
          case Some(o) => storage.getCheckpointSize(o)
          case None    => Option.empty[Long].pure[F]
        }
      case _ => Option.empty[Long].pure[F]
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
