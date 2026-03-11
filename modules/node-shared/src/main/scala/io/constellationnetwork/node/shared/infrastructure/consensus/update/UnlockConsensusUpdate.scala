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
  * '''Adaptive tiered thresholds:''' Thresholds adapt based on how many facilitators actually voted (sent ACKs), allowing progress even
  * when many peers are unresponsive:
  *
  *   - '''DEFER''' (voterCount < ceil(N/5)): Too few voters for a safe decision. The unlock is deferred and the re-stall mechanism will
  *     retry with fresh ACKs.
  *   - '''DEGRADED''' (voterCount < ceil(N/3)): Requires 2/3 supermajority of voters for both keep and remove decisions.
  *   - '''FALLBACK''' (voterCount < (N+1)/2): Simple majority of voters suffices for keep/remove decisions.
  *   - '''NORMAL''' (voterCount >= (N+1)/2): Standard thresholds based on total facilitator count.
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

          val n = facilitators.size
          val voterCount = facilitators.count(f => acksMap.contains((f, collectingKind)))

          val degradedQuorum = math.ceil(n.toDouble / 5).toInt.max(2)
          val standardQuorum = math.ceil(n.toDouble / 3).toInt.max(2)
          val normalKeepThreshold = (n + 1) / 2

          val maybeThresholds: Option[(Int, Int)] = {
            val superThreshold = math.ceil(voterCount.toDouble * 2 / 3).toInt.max(1)
            if (voterCount < degradedQuorum) none
            else if (voterCount < standardQuorum) (superThreshold, superThreshold).some
            else if (voterCount < normalKeepThreshold) ((voterCount + 1) / 2, voterCount / 2 + 1).some
            else (normalKeepThreshold, n / 2 + 1).some
          }

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
