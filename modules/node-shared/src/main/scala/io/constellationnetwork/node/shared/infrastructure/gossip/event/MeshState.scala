package io.constellationnetwork.node.shared.infrastructure.gossip.event

import cats.effect.{Async, Clock, Ref}
import cats.syntax.all._

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

/** Tracks the state of a peer in the gossip mesh.
  *
  * @param peerId
  *   The peer identifier
  * @param score
  *   Current peer score (higher = better)
  * @param messagesDelivered
  *   Number of messages successfully delivered
  * @param messagesFailed
  *   Number of messages that failed to deliver
  * @param lastSeenMs
  *   Timestamp of last successful communication (milliseconds)
  * @param inMesh
  *   Whether the peer is currently in our mesh
  */
case class PeerGossipState(
  peerId: PeerId,
  score: Double = 0.0,
  messagesDelivered: Long = 0,
  messagesFailed: Long = 0,
  lastSeenMs: Long = 0L,
  inMesh: Boolean = false,
  lastKnownOrdinal: Option[SnapshotOrdinal] = None,
  lastKnownHash: Option[Hash] = None
) {

  /** Update score after successful message delivery.
    */
  def recordDelivery(nowMs: Long): PeerGossipState =
    copy(
      messagesDelivered = messagesDelivered + 1,
      score = score + 1.0,
      lastSeenMs = nowMs
    )

  /** Update score after failed message delivery.
    */
  def recordFailure: PeerGossipState =
    copy(
      messagesFailed = messagesFailed + 1,
      score = score - 0.5
    )

  /** Apply time-based score decay.
    */
  def decayScore(decayFactor: Double): PeerGossipState =
    copy(score = score * decayFactor)

  /** Check if peer should be pruned from mesh based on score.
    */
  def shouldPrune(minScore: Double): Boolean =
    inMesh && score < minScore

  /** Check if peer is stale (no recent communication).
    */
  def isStale(nowMs: Long, maxAgeMs: Long): Boolean =
    nowMs - lastSeenMs > maxAgeMs
}

/** Manages the mesh overlay for gossip protocol.
  *
  * Implements mesh management inspired by libp2p Gossipsub:
  *   - Maintains a mesh of D peers for eager push
  *   - Tracks peer scores for prioritization
  *   - Grafts/prunes peers based on performance
  *   - Handles heartbeat for mesh maintenance
  */
trait MeshState[F[_]] {

  /** Get all peers currently in the mesh.
    */
  def getMeshPeers: F[Set[PeerId]]

  /** Get peer state by ID.
    */
  def getPeerState(peerId: PeerId): F[Option[PeerGossipState]]

  /** Add a peer to tracking (not necessarily to mesh).
    */
  def addPeer(peerId: PeerId): F[Unit]

  /** Remove a peer from tracking and mesh.
    */
  def removePeer(peerId: PeerId): F[Unit]

  /** Clear all mesh state. Used during fork recovery to reset stale peer info.
    */
  def clear: F[Unit]

  /** Graft a peer into the mesh.
    */
  def graft(peerId: PeerId): F[Boolean]

  /** Prune a peer from the mesh.
    */
  def prune(peerId: PeerId): F[Boolean]

  /** Record successful message delivery to peer.
    */
  def recordDelivery(peerId: PeerId): F[Unit]

  /** Record failed message delivery to peer.
    */
  def recordFailure(peerId: PeerId): F[Unit]

  /** Run heartbeat maintenance: decay scores, prune bad peers, graft new ones.
    */
  def heartbeat(availablePeers: Set[PeerId]): F[MeshHeartbeatResult]

  /** Get current mesh size.
    */
  def meshSize: F[Int]

  /** Check if mesh needs more peers.
    */
  def needsMorePeers(targetSize: Int): F[Boolean]

  /** Get chain tips from all peers that have reported them.
    */
  def getChainTips: F[Map[PeerId, ChainTip]]

  /** Update the chain tip for a peer.
    */
  def updateChainTip(peerId: PeerId, tip: ChainTip): F[Unit]

  /** Clear the chain tip for a peer (e.g. when sampling fails, so stale tips don't persist).
    */
  def clearChainTip(peerId: PeerId): F[Unit]
}

/** Result of a heartbeat operation.
  */
case class MeshHeartbeatResult(
  grafted: Set[PeerId],
  pruned: Set[PeerId],
  meshSize: Int
)

object MeshState {

  /** Configuration for mesh management.
    *
    * @param targetMeshSize
    *   D - target number of peers in mesh
    * @param minMeshSize
    *   D_lo - minimum mesh size before grafting
    * @param maxMeshSize
    *   D_hi - maximum mesh size before pruning
    * @param minScore
    *   Minimum score before forced pruning
    * @param scoreDecay
    *   Score decay factor per heartbeat (0.9 = 10% decay)
    * @param staleThresholdMs
    *   Time before unavailable peer is removed from tracking
    * @param rotationEnabled
    *   Whether to periodically rotate lowest-scoring mesh peer
    * @param rotationThreshold
    *   Only rotate if mesh is at target size and best candidate score exceeds current lowest by this margin
    */
  case class MeshConfig(
    targetMeshSize: Int = 6, // D - target mesh degree
    minMeshSize: Int = 4, // D_lo - minimum mesh size
    maxMeshSize: Int = 12, // D_hi - maximum mesh size
    minScore: Double = -100.0, // Minimum score before pruning
    scoreDecay: Double = 0.9, // Score decay factor per heartbeat
    staleThresholdMs: Long = 60000, // 60 seconds stale threshold
    rotationEnabled: Boolean = true, // Enable periodic mesh rotation for diversity
    rotationThreshold: Double = 5.0, // Score margin required to justify rotation
    rotationCooldownMs: Long = 30000 // Minimum time between rotations (30s) to prevent oscillation
  )

  /** Create a new MeshState.
    */
  def make[F[_]: Async](config: MeshConfig): F[MeshState[F]] =
    (Ref.of[F, Map[PeerId, PeerGossipState]](Map.empty), Ref.of[F, Long](0L)).mapN { (stateRef, lastRotationRef) =>
      new MeshStateImpl[F](stateRef, lastRotationRef, config)
    }

  private class MeshStateImpl[F[_]: Async](
    stateRef: Ref[F, Map[PeerId, PeerGossipState]],
    lastRotationRef: Ref[F, Long],
    config: MeshConfig
  ) extends MeshState[F] {

    private def nowMs: F[Long] = Clock[F].realTime.map(_.toMillis)

    override def getMeshPeers: F[Set[PeerId]] =
      stateRef.get.map(_.filter(_._2.inMesh).keySet)

    override def getPeerState(peerId: PeerId): F[Option[PeerGossipState]] =
      stateRef.get.map(_.get(peerId))

    override def addPeer(peerId: PeerId): F[Unit] =
      nowMs.flatMap { now =>
        stateRef.update { state =>
          if (state.contains(peerId)) state
          else state + (peerId -> PeerGossipState(peerId, lastSeenMs = now))
        }
      }

    override def removePeer(peerId: PeerId): F[Unit] =
      stateRef.update(_ - peerId)

    override def clear: F[Unit] =
      stateRef.set(Map.empty)

    override def graft(peerId: PeerId): F[Boolean] =
      nowMs.flatMap { now =>
        stateRef.modify { state =>
          state.get(peerId) match {
            case Some(peerState) if !peerState.inMesh =>
              val updated = state + (peerId -> peerState.copy(inMesh = true))
              (updated, true)
            case Some(_) =>
              (state, false) // Already in mesh
            case None =>
              val newState = PeerGossipState(peerId, inMesh = true, lastSeenMs = now)
              (state + (peerId -> newState), true)
          }
        }
      }

    override def prune(peerId: PeerId): F[Boolean] =
      stateRef.modify { state =>
        state.get(peerId) match {
          case Some(peerState) if peerState.inMesh =>
            val updated = state + (peerId -> peerState.copy(inMesh = false))
            (updated, true)
          case _ =>
            (state, false)
        }
      }

    override def recordDelivery(peerId: PeerId): F[Unit] =
      nowMs.flatMap { now =>
        stateRef.update { state =>
          state.get(peerId).fold(state) { peerState =>
            state + (peerId -> peerState.recordDelivery(now))
          }
        }
      }

    override def recordFailure(peerId: PeerId): F[Unit] =
      stateRef.update { state =>
        state.get(peerId).fold(state) { peerState =>
          state + (peerId -> peerState.recordFailure)
        }
      }

    override def heartbeat(availablePeers: Set[PeerId]): F[MeshHeartbeatResult] =
      for {
        now <- nowMs
        lastRotation <- lastRotationRef.get
        rotationAllowed = config.rotationEnabled && (now - lastRotation >= config.rotationCooldownMs)
        result <- stateRef.modify { state =>
          // 1. Apply score decay
          val decayed = state.map { case (id, ps) => id -> ps.decayScore(config.scoreDecay) }

          // 2. Update lastSeenMs for peers that are still available (keeps them from going stale)
          //    and remove peers that are no longer available
          val refreshed = decayed.flatMap {
            case (id, ps) =>
              if (availablePeers.contains(id))
                Some(id -> ps.copy(lastSeenMs = now))
              else if (ps.isStale(now, config.staleThresholdMs))
                None // Only remove if stale AND not available
              else
                Some(id -> ps)
          }

          // 3. Prune low-scoring mesh peers
          val prunedIds = refreshed.filter {
            case (_, ps) =>
              ps.shouldPrune(config.minScore)
          }.keySet
          val afterPrune = refreshed.map {
            case (id, ps) =>
              if (prunedIds.contains(id)) id -> ps.copy(inMesh = false)
              else id -> ps
          }

          // 4. Count current mesh size
          val currentMeshSize = afterPrune.count(_._2.inMesh)

          // 5. Graft new peers if below target
          // Adaptive: when cluster is small enough, connect to everyone (full mesh).
          // Mesh optimization only matters for large networks.
          val effectiveTarget = math.min(availablePeers.size, config.maxMeshSize)
          val needToGraft = (effectiveTarget - currentMeshSize).max(0)
          val candidatesForGraft = availablePeers
            .diff(afterPrune.filter(_._2.inMesh).keySet) // Not already in mesh
            .toList
            .sortBy(id => afterPrune.get(id).map(-_.score).getOrElse(0.0)) // Prefer higher scores
            .take(needToGraft)

          // Only graft peers that aren't already in mesh
          val graftedIds = candidatesForGraft.filterNot(id => afterPrune.get(id).exists(_.inMesh)).toSet
          val afterGraft = candidatesForGraft.foldLeft(afterPrune) { (s, id) =>
            s.get(id) match {
              case Some(ps) if !ps.inMesh => s + (id -> ps.copy(inMesh = true))
              case Some(_)                => s // Already in mesh, no change
              case None                   => s + (id -> PeerGossipState(id, inMesh = true, lastSeenMs = now))
            }
          }

          // 6. Prune if above max
          val meshSizeAfterGraft = afterGraft.count(_._2.inMesh)
          val (afterMaxPrune, extraPruned) =
            if (meshSizeAfterGraft > config.maxMeshSize) {
              val meshPeers = afterGraft
                .filter(_._2.inMesh)
                .toList
                .sortBy(_._2.score) // Lowest scores first
              val toPruneExtra = meshPeers.take(meshSizeAfterGraft - config.maxMeshSize).map(_._1).toSet
              val pruned = afterGraft.map {
                case (id, ps) =>
                  if (toPruneExtra.contains(id)) id -> ps.copy(inMesh = false)
                  else id -> ps
              }
              (pruned, toPruneExtra)
            } else {
              (afterGraft, Set.empty[PeerId])
            }

          // 7. Rotation for diversity: if mesh is full and a non-mesh peer has significantly better score,
          //    swap out the lowest-scoring mesh peer. This prevents mesh stagnation in stable networks.
          //    A cooldown prevents oscillation in high-churn environments.
          val (finalState, rotatedOut, rotatedIn, didRotate) = {
            val currentMeshSize = afterMaxPrune.count(_._2.inMesh)
            if (rotationAllowed && currentMeshSize >= effectiveTarget) {
              val meshPeers = afterMaxPrune.filter(_._2.inMesh).toList.sortBy(_._2.score)
              val nonMeshCandidates = availablePeers
                .diff(afterMaxPrune.filter(_._2.inMesh).keySet)
                .toList
                .flatMap(id => afterMaxPrune.get(id).map(id -> _))
                .sortBy(-_._2.score) // Highest scores first

              (meshPeers.headOption, nonMeshCandidates.headOption) match {
                case (Some((lowestId, lowestState)), Some((candidateId, candidateState)))
                    if candidateState.score - lowestState.score > config.rotationThreshold =>
                  // Rotate: prune lowest, graft candidate
                  val rotated = afterMaxPrune
                    .updated(lowestId, lowestState.copy(inMesh = false))
                    .updated(candidateId, candidateState.copy(inMesh = true))
                  (rotated, Some(lowestId), Some(candidateId), true)
                case _ =>
                  (afterMaxPrune, None, None, false)
              }
            } else {
              (afterMaxPrune, None, None, false)
            }
          }

          val result = MeshHeartbeatResult(
            grafted = graftedIds ++ rotatedIn.toSet,
            pruned = prunedIds ++ extraPruned ++ rotatedOut.toSet,
            meshSize = finalState.count(_._2.inMesh)
          )

          (finalState, (result, didRotate))
        }
        _ <- if (result._2) lastRotationRef.set(now) else Async[F].unit
      } yield result._1

    override def meshSize: F[Int] =
      stateRef.get.map(_.count(_._2.inMesh))

    override def needsMorePeers(targetSize: Int): F[Boolean] =
      meshSize.map(_ < targetSize)

    override def getChainTips: F[Map[PeerId, ChainTip]] =
      stateRef.get.map { state =>
        state.flatMap {
          case (peerId, ps) =>
            (ps.lastKnownOrdinal, ps.lastKnownHash).mapN(ChainTip(_, _)).map(peerId -> _)
        }
      }

    override def updateChainTip(peerId: PeerId, tip: ChainTip): F[Unit] =
      nowMs.flatMap { now =>
        stateRef.update { state =>
          state.get(peerId) match {
            case Some(ps) =>
              state + (peerId -> ps.copy(lastKnownOrdinal = tip.ordinal.some, lastKnownHash = tip.snapshotHash.some))
            case None =>
              state + (peerId -> PeerGossipState(
                peerId,
                lastSeenMs = now,
                lastKnownOrdinal = tip.ordinal.some,
                lastKnownHash = tip.snapshotHash.some
              ))
          }
        }
      }

    override def clearChainTip(peerId: PeerId): F[Unit] =
      stateRef.update { state =>
        state.get(peerId) match {
          case Some(ps) => state + (peerId -> ps.copy(lastKnownOrdinal = none, lastKnownHash = none))
          case None     => state
        }
      }
  }
}
