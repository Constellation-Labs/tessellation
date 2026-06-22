package io.constellationnetwork.node.shared.infrastructure.gossip.event

import cats.Parallel
import cats.effect.std.Supervisor
import cats.effect.{Async, Clock, Ref}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.mempool.{EventMempool, MempoolRejectionReason}
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.{Peer, PeerId}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.{Hashed, SecurityProvider}

import fs2.Stream
import io.circe.{Decoder, Encoder}
import org.http4s.client.Client
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Daemon for gossiping events using a libp2p Gossipsub-inspired protocol.
  *
  * Key behaviors:
  *   - Startup: Wait for node to reach Ready state before gossiping
  *   - Mesh construction: Maintain overlay mesh of D peers with scoring
  *   - Eager push: Forward full events to mesh peers immediately
  *   - Lazy pull: IHAVE/IWANT for non-mesh peers
  *   - Heartbeat: Periodic mesh maintenance (graft/prune, score decay)
  *
  * @tparam F
  *   Effect type
  * @tparam Event
  *   The event type
  * @tparam Key
  *   The state key type (unused by daemon, but required for EventMempool type)
  */
trait EventGossipDaemon[F[_], Event, Key] {

  /** Start the daemon after node reaches Ready state */
  def start: F[Unit]

  /** Stop the daemon */
  def stop: F[Unit]

  /** Publish an event to the gossip network */
  def publish(event: Hashed[Event]): F[Unit]

  /** Check if an event has already been seen */
  def hasSeen(hash: Hash): F[Boolean]

  /** Mark an event as seen (called by routes after a successful mempool.add).
    *
    * This keeps the seenCache consistent with the mempool so the pull loop does not issue redundant IWANT requests for events that were
    * already received via push. The push route calls mempool.add directly (not via the daemon) to avoid an extra queue hop; this method
    * lets the route notify the daemon's seenCache of the result.
    */
  def markSeen(hash: Hash): F[Unit]

  /** Get current mesh state for monitoring */
  def getMeshInfo: F[MeshInfo]

  /** Clear mesh state for fork recovery. Resets all peer tracking and chain tips so the next heartbeat re-grafts fresh peers from cluster
    * storage.
    */
  def clearMesh: F[Unit]

  /** Snapshot of every mesh peer's most recently reported `(ordinal, hash)` chain tip.
    *
    * Populated by the heartbeat + pull loops via `meshState.updateChainTip`. Used by the B2 re-admission gate as the witness channel: a
    * peer currently in `readmissionCountdown` whose gossiped chain tip matches the committee's current tip is a candidate for
    * `AdmissionVote` emission. This is the only consensus-independent signal of peer liveness at tip — probation peers are excluded from
    * the committee so they cannot be witnessed via in-round `Facility` declarations.
    */
  def getPeerChainTips: F[Map[PeerId, ChainTip]]
}

/** Information about the current mesh state for monitoring.
  */
case class MeshInfo(
  meshSize: Int,
  meshPeers: Set[PeerId],
  seenHashCount: Int
)

/** Configuration for the event gossip daemon.
  */
case class EventGossipConfig(
  meshDegree: Int = 6, // D parameter (target peers in mesh)
  meshLow: Int = 4, // D_lo - minimum mesh size
  meshHigh: Int = 12, // D_hi - maximum mesh size
  heartbeatInterval: FiniteDuration = EventGossipConfig.defaultHeartbeatInterval,
  messageWindowSize: Int = 5, // History window for deduplication
  gossipFactor: Int = 3, // IHAVE to D_lazy peers
  publishTimeout: FiniteDuration = 5.seconds,
  fetchTimeout: FiniteDuration = 5.seconds,
  pullInterval: FiniteDuration = EventGossipConfig.defaultPullInterval,
  maxConcurrentPulls: Int = 3, // Max concurrent IHAVE/IWANT operations
  minPeerScore: Double = -100.0, // Minimum score before pruning
  scoreDecay: Double = 0.9, // Score decay factor per heartbeat
  staleThresholdMs: Long = 60000, // 60 seconds stale threshold
  maxSeenHashes: Int = 100000, // Maximum number of seen hashes to track
  seenHashTtlMs: Long = 300000, // 5 minutes TTL for seen hashes
  pullRetryAttempts: Int = 3, // Number of retry attempts for pull operations
  pullRetryBackoff: FiniteDuration = 500.millis // Initial backoff delay for retries
)

object EventGossipConfig {
  val defaultHeartbeatInterval: FiniteDuration = 10.seconds
  val defaultPullInterval: FiniteDuration = 20.seconds
}

// ---------------------------------------------------------------------------
// Component 1: SeenHashCache — TTL-based dedup cache
// ---------------------------------------------------------------------------

/** TTL-based dedup cache for seen event hashes. */
private[event] trait SeenHashCache[F[_]] {
  def hasSeen(hash: Hash): F[Boolean]
  def markSeen(hash: Hash): F[Unit]
  def size: F[Int]

  /** Direct access to the underlying key set (used by pull logic to diff against IHAVE). */
  def keySet: F[Set[Hash]]
}

private[event] object SeenHashCache {

  /** Create a SeenHashCache backed by a Map + insertion-order Vector for O(1) FIFO eviction.
    *
    * When at capacity, the oldest 25% of entries are dropped in O(k) time (k = entries to remove) rather than O(n log n) from sorting the
    * entire map. TTL-expired entries are also pruned lazily.
    */
  def make[F[_]: Async](maxSize: Int, ttlMs: Long): F[SeenHashCache[F]] =
    Ref.of[F, (Map[Hash, Long], Vector[Hash])]((Map.empty, Vector.empty)).map { ref =>
      new SeenHashCache[F] {

        override def hasSeen(hash: Hash): F[Boolean] =
          for {
            nowMs <- Clock[F].realTime.map(_.toMillis)
            state <- ref.get
          } yield state._1.get(hash).exists(ts => (nowMs - ts) < ttlMs)

        override def markSeen(hash: Hash): F[Unit] =
          for {
            nowMs <- Clock[F].realTime.map(_.toMillis)
            _ <- ref.update {
              case (map, order) =>
                if (map.contains(hash)) {
                  // Already tracked, just update timestamp
                  (map + (hash -> nowMs), order)
                } else {
                  // FIFO eviction: drop oldest 25% when at 90% capacity.
                  // Evicting before hitting maxSize means the O(n/4) foldLeft fires less
                  // frequently and never blocks insertion on a fully-saturated cache.
                  val threshold = maxSize * 9 / 10
                  val (evictedMap, evictedOrder) = if (map.size >= threshold) {
                    val toRemove = maxSize / 4
                    val (dropped, kept) = order.splitAt(toRemove)
                    val newMap = dropped.foldLeft(map)(_ - _)
                    (newMap, kept)
                  } else (map, order)
                  (evictedMap + (hash -> nowMs), evictedOrder :+ hash)
                }
            }
          } yield ()

        override def size: F[Int] = ref.get.map(_._1.size)

        override def keySet: F[Set[Hash]] = ref.get.map(_._1.keySet)
      }
    }
}

// ---------------------------------------------------------------------------
// Component 2: GossipPublisher — eager push to mesh peers
// ---------------------------------------------------------------------------

/** Publishes events eagerly to mesh peers. */
private[event] trait GossipPublisher[F[_], Event] {
  def publish(event: Hashed[Event]): F[Unit]
}

private[event] object GossipPublisher {

  def make[F[_]: Async: Parallel, Event](
    meshState: MeshState[F],
    client: EventGossipClient[F, Event],
    getGossipEligiblePeers: F[Set[Peer]]
  ): GossipPublisher[F, Event] = {
    val logger = Slf4jLogger.getLogger[F]

    new GossipPublisher[F, Event] {

      override def publish(event: Hashed[Event]): F[Unit] =
        for {
          meshPeerIds <- meshState.getMeshPeers
          allPeers <- getGossipEligiblePeers
          meshPeers = allPeers.filter(p => meshPeerIds.contains(p.id)).toList
          _ <- meshPeers.nonEmpty
            .pure[F]
            .ifM(
              ifTrue = {
                val push = EventPush(event.hash, event.signed)
                meshPeers.parTraverse { peer =>
                  client
                    .pushEvent(push)
                    .run(Peer.toP2PContext(peer))
                    .flatMap { success =>
                      success
                        .pure[F]
                        .ifM(
                          ifTrue = meshState.recordDelivery(peer.id) >>
                            logger.debug(s"Successfully pushed event ${event.hash.show} to peer ${peer.id.show}"),
                          ifFalse = meshState.recordFailure(peer.id) >>
                            logger.warn(s"Push rejected by peer ${peer.id.show} for event ${event.hash.show} (non-2xx response)")
                        )
                    }
                    .handleErrorWith { err =>
                      meshState.recordFailure(peer.id) >>
                        logger.warn(s"Failed to push event to peer ${peer.id.show}: ${err.getMessage}")
                    }
                }.void
              },
              ifFalse = logger.debug("No mesh peers available for push")
            )
        } yield ()
    }
  }
}

// ---------------------------------------------------------------------------
// Component 3: GossipPuller — lazy pull via IHAVE/IWANT
// ---------------------------------------------------------------------------

/** Pulls missing events from non-mesh peers using IHAVE/IWANT. */
private[event] trait GossipPuller[F[_], Event, Key] {
  def pullFromPeers: F[Unit]
}

private[event] object GossipPuller {

  def make[F[_]: Async: Parallel, Event, Key](
    meshState: MeshState[F],
    client: EventGossipClient[F, Event],
    mempool: EventMempool[F, Event, Key],
    seenCache: SeenHashCache[F],
    getGossipEligiblePeers: F[Set[Peer]],
    config: EventGossipConfig
  ): GossipPuller[F, Event, Key] = {
    val logger = Slf4jLogger.getLogger[F]

    new GossipPuller[F, Event, Key] {

      override def pullFromPeers: F[Unit] =
        for {
          peers <- getGossipEligiblePeers
          meshPeerIds <- meshState.getMeshPeers
          lazyPeers = peers.filterNot(p => meshPeerIds.contains(p.id)).take(config.gossipFactor)
          _ <- lazyPeers.toList.traverse_(peer => pullFromPeer(peer))
        } yield ()

      private def pullFromPeer(peer: Peer): F[Unit] =
        Stream
          .retry(
            doPullFromPeer(peer),
            delay = config.pullRetryBackoff,
            nextDelay = _ * 2,
            maxAttempts = config.pullRetryAttempts
          )
          .compile
          .drain
          .handleErrorWith { err =>
            logger.debug(s"Pull from ${peer.id.show} failed after retries: ${err.getMessage}")
          }

      private def doPullFromPeer(peer: Peer): F[Unit] =
        for {
          ihave <- client.getIHave.run(Peer.toP2PContext(peer))
          _ <- ihave.chainTip.traverse_(tip => meshState.updateChainTip(peer.id, tip))
          seen <- seenCache.keySet
          missing = ihave.hashes.diff(seen)
          _ <- missing.nonEmpty
            .pure[F]
            .ifM(
              ifFalse = Async[F].unit,
              ifTrue = client
                .requestEvents(IWantRequest(missing))
                .run(Peer.toP2PContext(peer))
                .flatMap { response =>
                  response.events.traverse_ {
                    case (hash, signedEvent) =>
                      mempool.add(signedEvent).flatMap {
                        case Right(_) =>
                          seenCache.markSeen(hash) >>
                            logger.debug(s"Received event ${hash.show} from peer ${peer.id.show}")
                        case Left(reason) =>
                          logger.debug(s"Event ${hash.show} rejected: ${MempoolRejectionReason.show.show(reason)}")
                      }
                  }
                }
            )
        } yield ()
    }
  }
}

// ---------------------------------------------------------------------------
// Component 4: GraftSyncer — sync events to newly grafted mesh peers
// ---------------------------------------------------------------------------

/** Syncs missing events to newly grafted mesh peers via IHAVE comparison. */
private[event] trait GraftSyncer[F[_], Event, Key] {
  def syncMissingToNewPeers(newPeers: List[Peer]): F[Unit]
}

private[event] object GraftSyncer {

  def make[F[_]: Async: Parallel, Event, Key](
    client: EventGossipClient[F, Event],
    mempool: EventMempool[F, Event, Key],
    meshState: MeshState[F]
  ): GraftSyncer[F, Event, Key] = {
    val logger = Slf4jLogger.getLogger[F]

    new GraftSyncer[F, Event, Key] {

      override def syncMissingToNewPeers(newPeers: List[Peer]): F[Unit] =
        newPeers.traverse_ { peer =>
          (for {
            theirHashes <- client.getIHave.run(Peer.toP2PContext(peer))
            _ <- theirHashes.chainTip.traverse_(tip => meshState.updateChainTip(peer.id, tip))
            snapshot <- mempool.snapshot()
            ourHashes = snapshot.hashes
            missing = ourHashes.diff(theirHashes.hashes)
            _ <- missing.nonEmpty
              .pure[F]
              .ifM(
                ifTrue = for {
                  _ <- logger.debug(s"Syncing ${missing.size} missing events to newly grafted peer ${peer.id.show}")
                  missingEntries = snapshot.entries.filter { case (h, _) => missing.contains(h) }
                  _ <- missingEntries.toList.traverse_ {
                    case (hash, entry) =>
                      val push = EventPush(hash, entry.hashed.signed)
                      client
                        .pushEvent(push)
                        .run(Peer.toP2PContext(peer))
                        .flatMap { success =>
                          success
                            .pure[F]
                            .ifM(
                              ifTrue = meshState.recordDelivery(peer.id) >>
                                logger.debug(s"Synced event ${hash.show} to peer ${peer.id.show}"),
                              ifFalse = meshState.recordFailure(peer.id) >>
                                logger.warn(s"Sync rejected by peer ${peer.id.show} for event ${hash.show} (non-2xx response)")
                            )
                        }
                        .handleErrorWith { err =>
                          meshState.recordFailure(peer.id) >>
                            logger.debug(s"Failed to sync event ${hash.show} to ${peer.id.show}: ${err.getMessage}")
                        }
                  }
                } yield (),
                ifFalse = logger.debug(s"Peer ${peer.id.show} already has all ${ourHashes.size} events")
              )
          } yield ()).handleErrorWith { err =>
            logger.debug(s"Failed to sync with newly grafted peer ${peer.id.show}: ${err.getMessage}")
          }
        }
    }
  }
}

// ---------------------------------------------------------------------------
// Factory + thin coordinator impl
// ---------------------------------------------------------------------------

object EventGossipDaemon {

  /** Create a new EventGossipDaemon with full P2P gossip capability.
    *
    * This is the primary factory method for production use. Events will be gossiped to mesh peers via the HTTP client.
    */
  def make[F[_]: Async: Parallel: SecurityProvider, Event: Encoder: Decoder, Key](
    mempool: EventMempool[F, Event, Key],
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    client: Client[F],
    session: Session[F],
    config: EventGossipConfig = EventGossipConfig(),
    getLocalChainTip: Option[F[Option[ChainTip]]] = None,
    onForkDetected: Option[ForkRecoveryInfo => F[Unit]] = None,
    forkLagThreshold: Long = 10,
    verifyHashAt: Option[HashAtOrdinalProbe[F]] = None
  )(implicit S: Supervisor[F]): F[EventGossipDaemon[F, Event, Key]] =
    for {
      seenCache <- SeenHashCache.make[F](config.maxSeenHashes, config.seenHashTtlMs)
      running <- Ref.of[F, Boolean](false)
      meshConfig = MeshState.MeshConfig(
        targetMeshSize = config.meshDegree,
        minMeshSize = config.meshLow,
        maxMeshSize = config.meshHigh,
        minScore = config.minPeerScore,
        scoreDecay = config.scoreDecay,
        staleThresholdMs = config.staleThresholdMs
      )
      meshState <- MeshState.make[F](meshConfig)
      gossipClient = EventGossipClient.make[F, Event](client, session)

      maybeForkDetector = getLocalChainTip.map(tip => ForkRecoveryDetector.make(meshState, tip, forkLagThreshold, verifyHashAt))

      getGossipEligiblePeers: F[Set[Peer]] = clusterStorage.getResponsivePeers.map { peers =>
        peers.filter(p => p.state == NodeState.Ready || p.state == NodeState.Observing)
      }

      publisher = GossipPublisher.make[F, Event](meshState, gossipClient, getGossipEligiblePeers)
      puller = GossipPuller.make[F, Event, Key](meshState, gossipClient, mempool, seenCache, getGossipEligiblePeers, config)
      graftSyncer = GraftSyncer.make[F, Event, Key](gossipClient, mempool, meshState)
    } yield
      new EventGossipDaemonImpl[F, Event, Key](
        seenCache,
        running,
        meshState,
        config,
        publisher,
        puller,
        graftSyncer,
        gossipClient,
        getGossipEligiblePeers,
        maybeForkDetector,
        onForkDetected,
        nodeStorage
      )
}

/** Implementation of EventGossipDaemon — thin coordinator composing focused components.
  */
private class EventGossipDaemonImpl[F[_]: Async: Parallel, Event, Key](
  seenCache: SeenHashCache[F],
  running: Ref[F, Boolean],
  meshState: MeshState[F],
  config: EventGossipConfig,
  publisher: GossipPublisher[F, Event],
  puller: GossipPuller[F, Event, Key],
  graftSyncer: GraftSyncer[F, Event, Key],
  gossipClient: EventGossipClient[F, Event],
  getGossipEligiblePeers: F[Set[Peer]],
  maybeForkRecoveryDetector: Option[ForkRecoveryDetector[F]],
  onForkDetected: Option[ForkRecoveryInfo => F[Unit]],
  nodeStorage: NodeStorage[F]
)(implicit S: Supervisor[F])
    extends EventGossipDaemon[F, Event, Key] {

  private val logger = Slf4jLogger.getLogger[F]

  /** Start the daemon. Waits for the node to reach Ready state AND for the cluster to have at least one other Ready peer before beginning
    * heartbeat and pull loops. This prevents the gossip daemon from generating P2P traffic during:
    *   - Snapshot download and chain building (would compete with consensus HTTP calls on the shared client pool)
    *   - Solo genesis rounds (genesis produces snapshots alone before validators join; gossip traffic during this window can shift
    *     consensus timing enough to cause facilitators-hash divergence, which triggers fork detection)
    *
    * The daemon can still receive events via routes (receiveEvent) before starting — those are buffered in the incoming queue.
    *
    * This method blocks until both conditions are met, then spawns the loops. It should be called within a Supervisor (e.g. via
    * Daemon.spawn) so the wait doesn't block node startup.
    */
  override def start: F[Unit] =
    logger.info("EventGossipDaemon waiting for node to reach Ready state...") >>
      nodeStorage.nodeStates
        .filter(_ === NodeState.Ready)
        .head
        .compile
        .drain >>
      logger.info("EventGossipDaemon node is Ready, waiting for cluster peers...") >>
      waitForClusterPeers >>
      running.set(true) >>
      startHeartbeatLoop >>
      startPullLoop >>
      logger.info("EventGossipDaemon started (node is Ready with cluster peers)")

  /** Wait until at least one other peer is in Ready state. This ensures the gossip daemon doesn't generate P2P traffic during solo genesis
    * rounds. Polls every 5 seconds.
    */
  private def waitForClusterPeers: F[Unit] =
    getGossipEligiblePeers.flatMap { peers =>
      if (peers.nonEmpty)
        logger.info(s"EventGossipDaemon found ${peers.size} eligible peer(s), proceeding")
      else
        Async[F].sleep(5.seconds) >> waitForClusterPeers
    }

  override def stop: F[Unit] =
    running.set(false) >>
      logger.info("EventGossipDaemon stopped")

  override def publish(event: Hashed[Event]): F[Unit] =
    for {
      isRunning <- running.get
      _ <- isRunning
        .pure[F]
        .ifM(
          ifTrue = seenCache.markSeen(event.hash) >>
            logger.debug(s"Publishing event ${event.hash.show} to gossip network") >>
            publisher.publish(event),
          ifFalse = logger.warn("Cannot publish: daemon not running")
        )
    } yield ()

  override def hasSeen(hash: Hash): F[Boolean] =
    seenCache.hasSeen(hash)

  override def markSeen(hash: Hash): F[Unit] =
    seenCache.markSeen(hash)

  override def getMeshInfo: F[MeshInfo] =
    for {
      meshPeers <- meshState.getMeshPeers
      seenCount <- seenCache.size
    } yield
      MeshInfo(
        meshSize = meshPeers.size,
        meshPeers = meshPeers,
        seenHashCount = seenCount
      )

  override def clearMesh: F[Unit] =
    meshState.clear >> logger.info("Mesh state cleared for fork recovery")

  override def getPeerChainTips: F[Map[PeerId, ChainTip]] =
    meshState.getChainTips

  private def startHeartbeatLoop: F[Unit] =
    S.supervise {
      Stream
        .awakeEvery[F](config.heartbeatInterval)
        .evalMap(_ => running.get)
        .filter(identity)
        .evalMap(_ =>
          (runHeartbeat >> Async[F].cede).handleErrorWith { e =>
            logger.warn(e)("Heartbeat iteration failed, will retry next interval")
          }
        )
        .compile
        .drain
    }.void

  private def runHeartbeat: F[Unit] =
    for {
      peers <- getGossipEligiblePeers
      availablePeerIds = peers.map(_.id).toSet
      result <- meshState.heartbeat(availablePeerIds)
      _ <- result.grafted.nonEmpty
        .pure[F]
        .ifM(
          ifTrue = for {
            _ <- logger.debug(s"Heartbeat: grafted ${result.grafted.size} peers to mesh")
            graftedPeers = peers.filter(p => result.grafted.contains(p.id)).toList
            _ <- graftSyncer.syncMissingToNewPeers(graftedPeers)
          } yield (),
          ifFalse = Async[F].unit
        )
      _ <- result.pruned.nonEmpty
        .pure[F]
        .ifM(
          ifTrue = logger.debug(s"Heartbeat: pruned ${result.pruned.size} peers from mesh"),
          ifFalse = Async[F].unit
        )
      // Sample chain tips from a few mesh peers into MeshState. This data feeds two independent
      // consumers: the fork-recovery detector (when enabled, in the block below) AND the B2 re-admission
      // witness channel in the consensus engine, which reads peer chain tips (getPeerChainTips) to
      // confirm a candidate has caught up to the committed tip. The pull loop only contacts NON-mesh
      // peers, so when the mesh covers all peers (small clusters / adaptive mesh) this heartbeat sampling
      // is the only path that collects mesh-peer tips. It was previously gated on the fork detector being
      // present, which starved the B2 gate on nodes that serve a chain tip but wire no fork detector
      // (e.g. currency-l0): a joining 2nd metagraph-L0 was never witnessed and never admitted. Run it
      // whenever there are mesh peers; peers that serve no tip contribute nothing. Fork DETECTION (acting
      // on this data) stays gated on the detector + handler below, so this does NOT enable fork recovery
      // for nodes that opted out -- it only populates the shared chain-tip view.
      _ <- {
        for {
          meshPeerIds <- meshState.getMeshPeers
          meshPeers = peers.filter(p => meshPeerIds.contains(p.id)).toList
          sampled = scala.util.Random.shuffle(meshPeers).take(3)
          _ <- logger.debug(
            s"Chain tip sampling: meshSize=${meshPeers.size} sampled=${sampled.size} availablePeers=${peers.size}"
          )
          _ <- sampled.traverse_ { peer =>
            gossipClient.getIHave
              .run(Peer.toP2PContext(peer))
              .flatMap { ihave =>
                ihave.chainTip match {
                  case Some(tip) =>
                    logger.debug(
                      s"Chain tip from peer ${peer.id.show}: ordinal=${tip.ordinal.value.value} hash=${tip.snapshotHash.show}"
                    ) >> meshState.updateChainTip(peer.id, tip)
                  case None =>
                    logger.debug(s"Peer ${peer.id.show} returned no chain tip in IHave response")
                }
              }
              .handleErrorWith(e =>
                logger.debug(s"Chain tip sampling failed for peer ${peer.id.show}: ${e.getMessage}") >>
                  meshState.clearChainTip(peer.id)
              )
          }
        } yield ()
      }
      // Proactive fork detection — clear stale mesh before triggering recovery
      _ <- (maybeForkRecoveryDetector, onForkDetected).mapN { (detector, handler) =>
        detector.detectForkDivergence.flatMap(_.traverse_ { info =>
          clearMesh >> handler(info)
        })
      }.sequence_
    } yield ()

  private def startPullLoop: F[Unit] =
    S.supervise {
      Stream
        .awakeEvery[F](config.pullInterval)
        .evalMap(_ => running.get)
        .filter(identity)
        .evalMap(_ =>
          (puller.pullFromPeers >> Async[F].cede).handleErrorWith { e =>
            logger.warn(e)("Pull iteration failed, will retry next interval")
          }
        )
        .compile
        .drain
    }.void
}
