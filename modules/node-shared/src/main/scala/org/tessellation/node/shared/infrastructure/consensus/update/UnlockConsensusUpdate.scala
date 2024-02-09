package org.tessellation.node.shared.infrastructure.consensus.update

import cats.Monad
import cats.data.StateT
import cats.syntax.all._

import org.tessellation.node.shared.infrastructure.consensus.{Facilitators, LockStatus, RemovedFacilitators}
import org.tessellation.schema.peer.PeerId

import monocle.Lens

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
          val votingResult =
            _facilitators.get(state).value.foldLeft(_facilitators.get(state).value.map(_ -> initialVotes).toMap) { (acc, facilitator) =>
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

          val keepThreshold = (_facilitators.get(state).value.size + 1) / 2
          val removeThreshold = _facilitators.get(state).value.size / 2 + 1

          _facilitators
            .get(state)
            .value
            .traverse { peerId =>
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
            .map {
              _.partitionMap {
                case (peerId, decision) => Either.cond(decision, peerId, peerId)
              }
            }
            .map {
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
