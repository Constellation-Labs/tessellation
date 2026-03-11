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
  * '''Deterministic N-based thresholds:''' Thresholds are computed solely from N (the total facilitator count), which all nodes agree on.
  * This ensures that given the same set of ACK votes, all nodes make identical keep/remove decisions — preventing divergent facilitator
  * lists that would trigger unnecessary fork recovery.
  *
  *   - keepThreshold = (N + 1) / 2 — a peer is kept if at least half the facilitators vouch for it
  *   - removeThreshold = N / 2 + 1 — a peer is removed if a strict majority votes against it
  *   - keepThreshold + removeThreshold = N + 1 > N, guaranteeing mutual exclusivity (no peer can be both kept and removed)
  *
  * If fewer than ceil(N/3) facilitators have sent ACKs, the unlock is '''deferred''' (too few voters for any decision). The stall
  * detector's re-stall mechanism will spread ACKs again. If the unlock never succeeds after maxStallCycles, the round is abandoned and a
  * new round begins.
  *
  * '''Safety floor:''' After computing keep/remove decisions, if the number of kept facilitators would fall below `MinFacilitatorCount`
  * (currently 2), the unlock is aborted — the state remains `Closed` and the round is deferred to the stall detector. This prevents a
  * catastrophic scenario where stale ACKs (e.g., from a global latency spike where no declarations arrived before lock) cause all
  * facilitators to be voted out, leaving `facilitatorCount=0` and an irrecoverable cluster.
  *
  * After unlock transitions `Closed → Reopened` and removes unresponsive peers, `advanceStatus` can run again with the reduced facilitator
  * list, allowing the round to proceed.
  */
object UnlockConsensusUpdate {

  /** Minimum number of facilitators that must survive an unlock. If the voting result would leave fewer than this many peers, the unlock is
    * aborted and deferred to the stall detector (which will either retry ACKs or abandon the round after maxStallCycles).
    */
  val MinFacilitatorCount: Int = 2

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

          val n = facilitators.size
          val voterCount = facilitators.count(f => acksMap.contains((f, collectingKind)))

          val minVotersRequired = math.ceil(n.toDouble / 3).toInt.max(2)
          val keepThreshold = (n + 1) / 2
          val removeThreshold = n / 2 + 1

          val maybeThresholds: Option[(Int, Int)] =
            if (voterCount < minVotersRequired) none
            else (keepThreshold, removeThreshold).some

          maybeThresholds.flatMap {
            case (keepThreshold, removeThreshold) =>
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
          }.filter {
            case (_, keptFacilitators) => keptFacilitators.size >= MinFacilitatorCount
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
