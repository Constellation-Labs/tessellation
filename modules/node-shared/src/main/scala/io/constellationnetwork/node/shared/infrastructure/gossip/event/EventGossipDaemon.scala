package io.constellationnetwork.node.shared.infrastructure.gossip.event

import cats.Parallel
import cats.effect.std.{Queue, Supervisor}
import cats.effect.{Async, Clock, Ref}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
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

  /** Stream of events received from the network */
  def subscribe: Stream[F, Hashed[Event]]

  /** Check if an event has already been seen */
  def hasSeen(hash: Hash): F[Boolean]

  /** Receive an event from the network (via routes) */
  def receiveEvent(event: Hashed[Event]): F[Boolean]

  /** Get current mesh state for monitoring */
  def getMeshInfo: F[MeshInfo]
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
  heartbeatInterval: FiniteDuration = 1.second,
  messageWindowSize: Int = 5, // History window for deduplication
  gossipFactor: Int = 3, // IHAVE to D_lazy peers
  publishTimeout: FiniteDuration = 5.seconds,
  fetchTimeout: FiniteDuration = 5.seconds,
  pullInterval: FiniteDuration = 2.seconds, // How often to pull from peers
  maxConcurrentPulls: Int = 3, // Max concurrent IHAVE/IWANT operations
  minPeerScore: Double = -100.0, // Minimum score before pruning
  scoreDecay: Double = 0.9, // Score decay factor per heartbeat
  staleThresholdMs: Long = 60000, // 60 seconds stale threshold
  maxSeenHashes: Int = 100000, // Maximum number of seen hashes to track
  seenHashTtlMs: Long = 300000, // 5 minutes TTL for seen hashes
  pullRetryAttempts: Int = 3, // Number of retry attempts for pull operations
  pullRetryBackoff: FiniteDuration = 500.millis // Initial backoff delay for retries
)

object EventGossipDaemon {

  /** Create a new EventGossipDaemon with full P2P gossip capability.
    *
    * This is the primary factory method for production use. Events will be gossiped to mesh peers via the HTTP client.
    */
  def make[F[_]: Async: Parallel: SecurityProvider, Event: Encoder: Decoder, Key](
    mempool: EventMempool[F, Event, Key],
    clusterStorage: ClusterStorage[F],
    client: Client[F],
    session: Session[F],
    config: EventGossipConfig = EventGossipConfig()
  )(implicit S: Supervisor[F]): F[EventGossipDaemon[F, Event, Key]] =
    for {
      incomingQueue <- Queue.unbounded[F, Hashed[Event]]
      seenHashes <- Ref.of[F, Map[Hash, Long]](Map.empty) // Hash -> timestamp for TTL
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
    } yield
      new EventGossipDaemonImpl[F, Event, Key](
        mempool,
        clusterStorage,
        incomingQueue,
        seenHashes,
        running,
        meshState,
        config,
        gossipClient
      )
}

/** Implementation of EventGossipDaemon with full P2P gossip and mesh management.
  */
private class EventGossipDaemonImpl[F[_]: Async: Parallel, Event, Key](
  mempool: EventMempool[F, Event, Key],
  clusterStorage: ClusterStorage[F],
  incomingQueue: Queue[F, Hashed[Event]],
  seenHashes: Ref[F, Map[Hash, Long]], // Hash -> timestamp for TTL-based eviction
  running: Ref[F, Boolean],
  meshState: MeshState[F],
  config: EventGossipConfig,
  client: EventGossipClient[F, Event]
)(implicit S: Supervisor[F])
    extends EventGossipDaemon[F, Event, Key] {

  private val logger = Slf4jLogger.getLogger[F]

  /** Get peers eligible for gossip mesh: both Ready and Observing peers.
    *
    * We include Observing peers because they can participate in consensus as facilitators, so they need to receive gossiped events to have
    * correct hash intersection.
    */
  private def getGossipEligiblePeers: F[Set[Peer]] =
    clusterStorage.getResponsivePeers.map { peers =>
      peers.filter(p => p.state == NodeState.Ready || p.state == NodeState.Observing)
    }

  override def start: F[Unit] =
    running.set(true) >>
      startHeartbeatLoop >>
      startPullLoop >>
      logger.info("EventGossipDaemon started")

  override def stop: F[Unit] =
    running.set(false) >>
      logger.info("EventGossipDaemon stopped")

  override def publish(event: Hashed[Event]): F[Unit] =
    for {
      isRunning <- running.get
      _ <- isRunning
        .pure[F]
        .ifM(
          ifTrue = doPublish(event),
          ifFalse = logger.warn("Cannot publish: daemon not running")
        )
    } yield ()

  private def doPublish(event: Hashed[Event]): F[Unit] =
    for {
      _ <- markSeen(event.hash)
      _ <- logger.debug(s"Publishing event ${event.hash.show} to gossip network")
      _ <- pushToMeshPeers(event, client)
    } yield ()

  private def pushToMeshPeers(
    event: Hashed[Event],
    client: EventGossipClient[F, Event]
  ): F[Unit] =
    for {
      meshPeerIds <- meshState.getMeshPeers
      allPeers <- getGossipEligiblePeers
      // Filter to only peers that are both in mesh and eligible for gossip (Ready or Observing)
      meshPeers = allPeers.filter(p => meshPeerIds.contains(p.id)).toList
      _ <- meshPeers.nonEmpty
        .pure[F]
        .ifM(
          ifTrue = {
            val push = EventPush(event.hash, event.signed)
            // Push to mesh peers in parallel, track success/failure
            meshPeers.parTraverse_ { peer =>
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
            }
          },
          ifFalse = logger.debug("No mesh peers available for push")
        )
    } yield ()

  override def subscribe: Stream[F, Hashed[Event]] =
    Stream.fromQueueUnterminated(incomingQueue)

  override def hasSeen(hash: Hash): F[Boolean] =
    for {
      nowMs <- Clock[F].realTime.map(_.toMillis)
      seen <- seenHashes.get
    } yield seen.get(hash).exists(ts => (nowMs - ts) < config.seenHashTtlMs)

  override def receiveEvent(event: Hashed[Event]): F[Boolean] =
    for {
      alreadySeen <- hasSeen(event.hash)
      isNew <- alreadySeen
        .pure[F]
        .ifM(
          ifTrue = false.pure[F],
          ifFalse = markSeen(event.hash) >> incomingQueue.offer(event).as(true)
        )
    } yield isNew

  override def getMeshInfo: F[MeshInfo] =
    for {
      meshPeers <- meshState.getMeshPeers
      seen <- seenHashes.get
    } yield
      MeshInfo(
        meshSize = meshPeers.size,
        meshPeers = meshPeers,
        seenHashCount = seen.size
      )

  /** Mark a hash as seen with TTL-based eviction.
    *
    * Evicts oldest entries when the cache exceeds maxSeenHashes.
    */
  private def markSeen(hash: Hash): F[Unit] =
    for {
      nowMs <- Clock[F].realTime.map(_.toMillis)
      _ <- seenHashes.update { current =>
        // Prune expired entries and evict oldest if over limit
        val pruned = current.filter { case (_, ts) => (nowMs - ts) < config.seenHashTtlMs }
        val evicted = if (pruned.size >= config.maxSeenHashes) {
          // Remove oldest 25% to avoid constant eviction
          val toRemove = pruned.size / 4
          pruned.toList.sortBy(_._2).drop(toRemove).toMap
        } else pruned
        evicted + (hash -> nowMs)
      }
    } yield ()

  /** Start the heartbeat loop for mesh maintenance.
    */
  private def startHeartbeatLoop: F[Unit] =
    S.supervise {
      Stream
        .awakeEvery[F](config.heartbeatInterval)
        .evalMap(_ => running.get)
        .filter(identity)
        .evalMap(_ => runHeartbeat)
        .compile
        .drain
    }.void

  private def runHeartbeat: F[Unit] =
    for {
      // Get ready peers from cluster (only peers in Ready state participate in gossip)
      peers <- getGossipEligiblePeers
      availablePeerIds = peers.map(_.id).toSet
      // Run heartbeat on mesh state
      result <- meshState.heartbeat(availablePeerIds)
      _ <- result.grafted.nonEmpty
        .pure[F]
        .ifM(
          ifTrue = for {
            _ <- logger.debug(s"Heartbeat: grafted ${result.grafted.size} peers to mesh")
            // Sync missing events to newly grafted peers using IHAVE comparison
            graftedPeers = peers.filter(p => result.grafted.contains(p.id)).toList
            _ <- syncMissingToNewPeers(graftedPeers)
          } yield (),
          ifFalse = Async[F].unit
        )
      _ <- result.pruned.nonEmpty
        .pure[F]
        .ifM(
          ifTrue = logger.debug(s"Heartbeat: pruned ${result.pruned.size} peers from mesh"),
          ifFalse = Async[F].unit
        )
    } yield ()

  /** Sync only missing events to newly grafted mesh peers.
    *
    * Uses IHAVE comparison to determine what the peer is missing, then pushes only those events. This ensures events published before mesh
    * formation are propagated without sending duplicates.
    */
  private def syncMissingToNewPeers(newPeers: List[Peer]): F[Unit] =
    newPeers.parTraverse_ { peer =>
      (for {
        // Get what the peer already has
        theirHashes <- client.getIHave.run(Peer.toP2PContext(peer))
        // Get our current mempool
        snapshot <- mempool.snapshot()
        ourHashes = snapshot.hashes
        // Find what we have that they don't
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

  /** Start the pull loop for IHAVE/IWANT protocol.
    */
  private def startPullLoop: F[Unit] =
    S.supervise {
      Stream
        .awakeEvery[F](config.pullInterval)
        .evalMap(_ => running.get)
        .filter(identity)
        .evalMap(_ => pullFromPeers)
        .compile
        .drain
    }.void

  private def pullFromPeers: F[Unit] =
    for {
      peers <- getGossipEligiblePeers
      meshPeerIds <- meshState.getMeshPeers
      // Select non-mesh Ready peers for lazy pull
      lazyPeers = peers.filterNot(p => meshPeerIds.contains(p.id)).take(config.gossipFactor)
      _ <- lazyPeers.toList.traverse_(peer => pullFromPeer(peer, client))
    } yield ()

  private def pullFromPeer(
    peer: Peer,
    client: EventGossipClient[F, Event]
  ): F[Unit] =
    Stream
      .retry(
        doPullFromPeer(peer, client),
        delay = config.pullRetryBackoff,
        nextDelay = _ * 2,
        maxAttempts = config.pullRetryAttempts
      )
      .compile
      .drain
      .handleErrorWith { err =>
        logger.debug(s"Pull from ${peer.id.show} failed after retries: ${err.getMessage}")
      }

  private def doPullFromPeer(
    peer: Peer,
    client: EventGossipClient[F, Event]
  ): F[Unit] =
    for {
      // Get IHAVE from peer
      ihave <- client.getIHave.run(Peer.toP2PContext(peer))
      // Find missing hashes (use keySet since seenHashes is now a Map)
      seen <- seenHashes.get
      missing = ihave.hashes.diff(seen.keySet)
      // Request missing events
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
                      markSeen(hash) >>
                        logger.debug(s"Received event ${hash.show} from peer ${peer.id.show}")
                    case Left(reason) =>
                      logger.debug(s"Event ${hash.show} rejected: ${MempoolRejectionReason.show.show(reason)}")
                  }
              }
            }
        )
    } yield ()
}
