package io.constellationnetwork.node.shared.infrastructure.consensus.update

import cats.Monad
import cats.data.StateT
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.schema.peer.PeerId

import monocle.Lens

/** Unlock voting logic for consensus stall recovery.
  *
  * When a consensus round stalls (not all facilitators declared within the timeout), the round is locked and ACKs are spread. Each ACK
  * contains the set of peer IDs that the sender considers "present" (responsive). This module tallies ACK votes to decide which peers to
  * keep and which to remove.
  *
  * '''Adaptive threshold semantics:''' The unlock mechanism uses tiered adaptive thresholds to handle network partitions and high-latency
  * peers while maintaining safety:
  *
  * 1. '''Normal mode''' (voterCount >= 50% of facilitators): Use standard majority thresholds based on total facilitator count. This is the
  *    expected happy path with good network connectivity.
  *
  * 2. '''Fallback mode''' (voterCount >= 1/3 but < 50%): Switch to simple majority-of-voters thresholds. This allows the responsive subset
  *    to converge rather than deadlocking when some peers are slow or partitioned.
  *
  * 3. '''Degraded mode''' (voterCount >= 1/5 but < 1/3): Use supermajority (2/3) of voters thresholds. This is a safety valve for
  *    high-latency networks where even 1/3 quorum is hard to reach. The stronger agreement requirement compensates for the lower quorum,
  *    preventing reckless decisions while avoiding permanent deadlock.
  *
  * 4. '''No unlock''' (voterCount < 1/5): Defer entirely and wait for more ACKs. With so few participants, no safe decision can be made.
  *
  * The tiered approach ensures: (a) the largest responsive subset can make progress, (b) tiny minorities cannot make unilateral decisions,
  * and (c) high-latency peers don't cause permanent deadlocks.
  *
  * After unlock transitions `Closed → Reopened` and removes unresponsive peers, `advanceStatus` can run again with the reduced facilitator
  * list, allowing the round to proceed.
  */
object UnlockConsensusUpdate {

  def tryUnlock[F[_]: Monad, S, K](acksMap: Map[(PeerId, K), Set[PeerId]])(maybeCollectingKind: S => Option[K])(
    implicit _lockStatus: Lens[S, LockStatus],
    _facilitators: Lens[S, Facilitators],
    _removedFacilitators: Lens[S, RemovedFacilitators]
  ): StateT[F, S, Unit] =
    StateT.modify { state =>
      if (_lockStatus.get(state) =!= LockStatus.Closed)
        state
      else {
        val (voteKeep, voteRemove, initialVotes) = ((1, 0), (0, 1), (0, 0))

        maybeCollectingKind(state).flatMap { collectingKind =>
          val facilitators = _facilitators.get(state).value

          val votingResult =
            facilitators.foldLeft(facilitators.map(_ -> initialVotes).toMap) { (acc, facilitator) =>
              acksMap
                .get((facilitator, collectingKind))
                .map { ack =>
                  acc.map {
                    case (peerId, votes) =>
                      if (ack.contains(peerId))
                        (peerId, votes |+| voteKeep)
                      else
                        (peerId, votes |+| voteRemove)
                  }
                }
                .getOrElse(acc)
            }

          val normalKeepThreshold = (facilitators.size + 1) / 2
          val normalRemoveThreshold = facilitators.size / 2 + 1

          // Count how many facilitators actually submitted ACKs
          val voterCount = facilitators.count(f => acksMap.contains((f, collectingKind)))

          // Quorum tiers for adaptive unlock:
          // - Standard quorum: 1/3 of facilitators (Byzantine fault tolerance)
          // - Degraded quorum: 1/5 of facilitators (for high-latency/partition recovery)
          //   BUT requires supermajority (2/3) agreement among voters
          val standardQuorum = math.max(facilitators.size / 3, 2)
          val degradedQuorum = math.max(facilitators.size / 5, 2)

          // Adaptive thresholds based on voter participation:
          // - Normal mode: standard majority thresholds when participation is high
          // - Fallback mode: majority-of-voters when above standard quorum
          // - Degraded mode: supermajority-of-voters when above degraded quorum
          //   (compensates for lower quorum with stronger agreement requirement)
          // - No unlock: defer if below degraded quorum
          val maybeThresholds: Option[(Int, Int)] =
            if (voterCount < degradedQuorum)
              // Below even degraded quorum: defer unlock, wait for more ACKs
              none
            else if (voterCount < standardQuorum)
              // Degraded mode: low participation, require supermajority (2/3) of voters
              // This is a safety valve for high-latency networks - harder to reach
              // but prevents permanent deadlock when no partition has 1/3
              val superKeep = (voterCount * 2 + 2) / 3    // ceil(2/3 * voterCount)
              val superRemove = (voterCount * 2 + 2) / 3
              (superKeep, superRemove).some
            else if (voterCount < normalKeepThreshold)
              // Fallback mode: enough for standard quorum but not normal majority
              // Use simple majority-of-voters to allow responsive subset to converge
              ((voterCount + 1) / 2, voterCount / 2 + 1).some
            else
              // Normal mode: standard thresholds based on total facilitator count
              (normalKeepThreshold, normalRemoveThreshold).some

          maybeThresholds.flatMap { case (keepThreshold, removeThreshold) =>
            facilitators.traverse { peerId =>
              votingResult.get(peerId).flatMap {
                case (votesKeep, votesRemove) =>
                  if (votesKeep >= keepThreshold)
                    (peerId, true).some
                  else if (votesRemove >= removeThreshold)
                    (peerId, false).some
                  else
                    none
              }
            }
          }.map {
            _.partitionMap {
              case (peerId, decision) => Either.cond(decision, peerId, peerId)
            }
          }.map {
            case (removedFacilitators, keptFacilitators) =>
              val updateState =
                _lockStatus.modify {
                  case LockStatus.Closed => LockStatus.Reopened
                  case other             => other
                }
                  .andThen(_facilitators.replace(Facilitators(keptFacilitators)))
                  .andThen(_removedFacilitators.modify(r => RemovedFacilitators(r.value.union(removedFacilitators.toSet))))

              updateState(state)
          }
        }
          .getOrElse(state)
      }
    }
}
