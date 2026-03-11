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
  * '''Voter-based threshold fallback:''' When fewer peers respond with ACKs than the normal majority threshold (which is based on total
  * facilitator count), the normal thresholds become mathematically unreachable — e.g., with 44 facilitators but only 19 ACKs,
  * keepThreshold=22 can never be met. In this case, we fall back to majority-of-voters thresholds so the unlock can succeed and the round
  * can make progress with the responsive subset.
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

          // When fewer peers responded with ACKs than the normal majority threshold,
          // normal thresholds are mathematically unreachable (max possible votes = voterCount < threshold).
          // Fall back to majority-of-voters thresholds to prevent permanent round deadlock.
          val (keepThreshold, removeThreshold) =
            if (voterCount > 0 && voterCount < normalKeepThreshold)
              ((voterCount + 1) / 2, voterCount / 2 + 1)
            else
              (normalKeepThreshold, normalRemoveThreshold)

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
