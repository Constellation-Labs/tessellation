package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.kernel.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId

/** Tracks per-peer consensus quality metrics.
  *
  * ==Purpose==
  *
  * Measures how reliably each peer participates in consensus rounds:
  *   - '''completionRate''': fraction of rounds that completed successfully when this peer was a facilitator
  *   - '''viewChangeRate''': fraction of rounds where this peer caused a view change as leader
  *
  * ==Usage==
  *
  * Quality scores are '''local to this node''' and may differ across nodes. They are safe to use for:
  *   - Adjusting stall detection timeouts (shorter patience for known-bad leaders)
  *   - Observability metrics (Prometheus export)
  *   - Informing operator decisions about node health
  *
  * Quality scores '''must not''' be used for deterministic leader/facilitator selection, since different nodes may compute different
  * scores. For deterministic quality-weighted selection, scores would need to be agreed upon via consensus (future work).
  *
  * ==Score Computation==
  *
  * {{{
  *   qualityScore = completionRate * (1 - viewChangeRate)
  *
  *   where:
  *     completionRate = roundsCompleted / roundsParticipated
  *     viewChangeRate = viewChangesCaused / roundsParticipated
  * }}}
  *
  * A peer with no data gets a score of 1.0 (benefit of the doubt). Scores range from 0.0 (worst) to 1.0 (best).
  */
trait PeerQualityTracker[F[_]] {

  /** Record that all facilitators in a round participated in a successful consensus. */
  def recordRoundSuccess(facilitators: Set[PeerId]): F[Unit]

  /** Record that a leader failed to propose in time, causing a view change. */
  def recordViewChange(failedLeader: PeerId): F[Unit]

  /** Record that all facilitators in a round experienced an abandoned round. */
  def recordRoundAbandoned(facilitators: Set[PeerId]): F[Unit]

  /** Record peers that were missing (didn't declare) when a round was abandoned. These peers should be excluded from the next round's
    * facilitator selection to prevent the same deadlock from repeating.
    *
    * While this is local (not consensus-agreed), after 5+ stall cycles (65s+) all honest nodes will have the same view of who's missing.
    * The facilitatorsHash check in the Proposals phase catches any rare disagreement.
    */
  def recordAbandonedMissingPeers(missing: Set[PeerId]): F[Unit]

  /** Get and atomically clear the set of peers missing from abandoned rounds. Used by ConsensusStateCreator to exclude these peers from the
    * retry's facilitator set.
    */
  def getAndClearAbandonedMissingPeers: F[Set[PeerId]]

  /** Get quality score for a single peer (0.0 = worst, 1.0 = best). */
  def getQualityScore(peerId: PeerId): F[Double]

  /** Get quality scores for all tracked peers. */
  def getQualityScores: F[Map[PeerId, Double]]
}

object PeerQualityTracker {

  private[consensus] case class PeerMetrics(
    roundsParticipated: Long,
    roundsCompleted: Long,
    viewChangesCaused: Long
  ) {
    def qualityScore: Double =
      if (roundsParticipated == 0L) 1.0
      else {
        val completionRate = roundsCompleted.toDouble / roundsParticipated
        val viewChangeRate = viewChangesCaused.toDouble / roundsParticipated
        (completionRate * (1.0 - viewChangeRate)).max(0.0).min(1.0)
      }
  }

  private val emptyMetrics: PeerMetrics = PeerMetrics(0L, 0L, 0L)

  /** Maximum tracked rounds per peer before counters are halved to prevent unbounded growth. */
  private val decayThreshold: Long = 10000L

  def make[F[_]: Async]: F[PeerQualityTracker[F]] =
    for {
      ref <- Ref.of[F, Map[PeerId, PeerMetrics]](Map.empty)
      abandonedRef <- Ref.of[F, Set[PeerId]](Set.empty)
    } yield
      new PeerQualityTracker[F] {

        def recordRoundSuccess(facilitators: Set[PeerId]): F[Unit] =
          ref.update { metrics =>
            maybeDecay(
              facilitators.foldLeft(metrics) { (m, pid) =>
                val current = m.getOrElse(pid, emptyMetrics)
                m.updated(
                  pid,
                  current.copy(
                    roundsParticipated = current.roundsParticipated + 1L,
                    roundsCompleted = current.roundsCompleted + 1L
                  )
                )
              }
            )
          }

        def recordViewChange(failedLeader: PeerId): F[Unit] =
          ref.update { metrics =>
            val current = metrics.getOrElse(failedLeader, emptyMetrics)
            metrics.updated(
              failedLeader,
              current.copy(viewChangesCaused = current.viewChangesCaused + 1L)
            )
          }

        def recordRoundAbandoned(facilitators: Set[PeerId]): F[Unit] =
          ref.update { metrics =>
            maybeDecay(
              facilitators.foldLeft(metrics) { (m, pid) =>
                val current = m.getOrElse(pid, emptyMetrics)
                m.updated(
                  pid,
                  current.copy(roundsParticipated = current.roundsParticipated + 1L)
                )
              }
            )
          }

        def recordAbandonedMissingPeers(missing: Set[PeerId]): F[Unit] =
          abandonedRef.update(_ ++ missing)

        def getAndClearAbandonedMissingPeers: F[Set[PeerId]] =
          abandonedRef.getAndSet(Set.empty)

        def getQualityScore(peerId: PeerId): F[Double] =
          ref.get.map(_.getOrElse(peerId, emptyMetrics).qualityScore)

        def getQualityScores: F[Map[PeerId, Double]] =
          ref.get.map(_.map { case (pid, m) => (pid, m.qualityScore) })
      }

  /** Halve all counters when any peer exceeds the decay threshold to keep recent data relevant.
    *
    * TODO: Repeated halving can push counters to 0 for active peers (e.g., (1, 2) → (0, 1) → pruned). Consider using max(1, c/2) for
    * roundsCompleted to prevent false pruning of peers who have completed rounds but have low absolute counts after multiple decay cycles.
    */
  private def maybeDecay(metrics: Map[PeerId, PeerMetrics]): Map[PeerId, PeerMetrics] =
    if (metrics.values.exists(_.roundsParticipated > decayThreshold))
      metrics.map {
        case (pid, m) =>
          pid -> PeerMetrics(
            roundsParticipated = m.roundsParticipated / 2L,
            roundsCompleted = m.roundsCompleted / 2L,
            viewChangesCaused = m.viewChangesCaused / 2L
          )
      }
    else metrics
}
