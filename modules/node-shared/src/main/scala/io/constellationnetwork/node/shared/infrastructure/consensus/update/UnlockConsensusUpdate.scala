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
  * '''Threshold semantics:''' Thresholds are based on the total facilitator count — keepThreshold = majority, removeThreshold = strict
  * majority. All facilitators in the voting result must receive a clear keep or remove decision for the unlock to proceed. If any peer's
  * vote tally is indeterminate (not enough votes to reach either threshold), the unlock is deferred (`traverse` returns `None`) and the
  * state remains `Closed`. The re-stall mechanism will retry with fresh ACKs after the re-stall timeout.
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

          val keepThreshold = (facilitators.size + 1) / 2
          val removeThreshold = facilitators.size / 2 + 1

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
