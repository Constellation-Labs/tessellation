package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.kernel.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.StallReport
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

/** Proof that a majority of facilitators agreed a round timed out.
  *
  * Modeled after HotStuff's TimeoutCertificate: formed when a majority of facilitators independently report the same stall. The
  * `agreedMissingPeers` field (intersection of all signers' missing peer lists) enables deterministic eviction — all nodes compute the same
  * removal set from the same TC data.
  */
@derive(eqv, show, encoder, decoder)
case class TimeoutCertificate(
  roundId: SnapshotOrdinal,
  leaderEpoch: Int,
  signers: Set[PeerId],
  agreedMissingPeers: Set[PeerId]
)

/** Event-driven aggregator for StallReport messages, modeled after Flow's TimeoutAggregator.
  *
  * Receives StallReports from facilitators (local detection or remote gossip) and forms a [[TimeoutCertificate]] when a majority agree the
  * round is stalled. The TC fires a callback to [[ViewChangeManager]] which performs deterministic eviction and leader rotation.
  *
  * ==Architecture==
  *
  * Unlike the previous polling-based EvictionVoteTracker, this component is '''event-driven''': each incoming StallReport is processed
  * immediately via [[addStallReport]], and the TC callback fires the instant the quorum-completing report arrives. No polling loop, no
  * timing races with gossip delivery.
  *
  * ==Flow==
  * {{{
  *   StallDetector detects timeout → broadcasts StallReport + addStallReport(self, report)
  *   RumorHandler receives remote StallReport → addStallReport(sender, report)
  *   When collected.size >= majority → compute TC → fire onTcFormed callback
  * }}}
  *
  * @see
  *   [[ViewChangeManager]] for TC-triggered view changes
  */
trait TimeoutAggregator[F[_]] {

  /** Process a StallReport from a facilitator (local or remote). If this report completes a majority, the onTcFormed callback fires
    * immediately.
    */
  def addStallReport(sender: PeerId, key: Any, report: StallReport): F[Unit]

  /** Reset state for a new round. Called when a round completes or a new round starts. */
  def reset: F[Unit]

  /** Update the facilitator set for majority calculation. Called when a round starts. */
  def setFacilitators(facilitators: List[PeerId], roundId: SnapshotOrdinal, leaderEpoch: Int): F[Unit]
}

object TimeoutAggregator {

  def make[F[_]: Async](
    onTcFormed: TimeoutCertificate => F[Unit]
  ): F[TimeoutAggregator[F]] =
    for {
      collectedRef <- Ref.of[F, Map[PeerId, StallReport]](Map.empty)
      facilitatorsRef <- Ref.of[F, List[PeerId]](List.empty)
      roundIdRef <- Ref.of[F, Option[SnapshotOrdinal]](None)
      leaderEpochRef <- Ref.of[F, Int](0)
      firedRef <- Ref.of[F, Boolean](false)
    } yield
      new TimeoutAggregator[F] {

        def addStallReport(sender: PeerId, key: Any, report: StallReport): F[Unit] =
          for {
            facilitators <- facilitatorsRef.get
            // Only accept reports from current facilitators
            _ <- (
              for {
                fired <- firedRef.get
                _ <- collectedRef.update(_.updated(sender, report)).unlessA(fired)
                collected <- collectedRef.get
                majority = (facilitators.size / 2) + 1
                _ <- (
                  for {
                    roundId <- roundIdRef.get
                    leaderEpoch <- leaderEpochRef.get
                    tc = buildTC(roundId, leaderEpoch, collected, facilitators.size)
                    _ <- firedRef.set(true)
                    _ <- onTcFormed(tc)
                  } yield ()
                ).whenA(collected.size >= majority && !fired)
              } yield ()
            ).whenA(facilitators.contains(sender))
          } yield ()

        def reset: F[Unit] =
          collectedRef.set(Map.empty) >> firedRef.set(false)

        def setFacilitators(facilitators: List[PeerId], roundId: SnapshotOrdinal, leaderEpoch: Int): F[Unit] =
          facilitatorsRef.set(facilitators) >>
            roundIdRef.set(Some(roundId)) >>
            leaderEpochRef.set(leaderEpoch) >>
            reset

        private def buildTC(
          maybeRoundId: Option[SnapshotOrdinal],
          leaderEpoch: Int,
          collected: Map[PeerId, StallReport],
          facilitatorsCount: Int
        ): TimeoutCertificate = {
          val signers = collected.keySet
          val totalSigners = signers.size
          val required = (totalSigners / 2) + 1
          // Majority-per-peer: a peer is evicted if >50% of TC signers reported it as missing.
          // This handles the common case where the missing peer can't vote for its own eviction
          // (it reports different peers as missing, or doesn't report at all).
          // Intersection would require ALL signers to agree, which fails when the missing peer
          // is itself a signer with a different view of who's absent.
          val allReportedMissing = collected.values.flatMap(_.missingPeers).toSet
          val candidateMissing = allReportedMissing.filter { peer =>
            collected.values.count(_.missingPeers.contains(peer)) >= required
          }
          // Eviction cap: never evict more than (N/2 - 1) peers via a single TC.
          // This prevents a group of non-participating peers from forming a TC that
          // evicts all validators. At most, a minority can be evicted per TC —
          // preserving the honest majority's control of the facilitator set.
          val maxEvictable = math.max(1, (facilitatorsCount / 2) - 1)
          val agreedMissing =
            if (candidateMissing.size > maxEvictable)
              // Keep only the peers with the MOST votes (most agreed-upon evictions)
              candidateMissing.toList
                .sortBy(peer => -collected.values.count(_.missingPeers.contains(peer)))
                .take(maxEvictable)
                .toSet
            else
              candidateMissing
          TimeoutCertificate(
            roundId = maybeRoundId.getOrElse(SnapshotOrdinal.MinValue),
            leaderEpoch = leaderEpoch,
            signers = signers,
            agreedMissingPeers = agreedMissing
          )
        }
      }
}
