package org.tessellation.sdk.infrastructure.consensus.update

import cats.Monad
import cats.data.StateT
import cats.syntax.all._

import org.tessellation.sdk.infrastructure.consensus.{ConsensusResources, Reopened}

object UnlockConsensusUpdate {

  def make[F[_]: Monad, Key, Artifact]: ConsensusStateUpdateFn[F, Key, Artifact, Unit] = (resources: ConsensusResources[Artifact]) =>
    StateT.modify { state =>
      if (state.notLocked)
        state
      else {
        val (voteKeep, voteRemove, initialVotes) = ((1, 0), (0, 1), (0, 0))

        state.maybeCollectingKind.flatMap { collectingKind =>
          val votingResult = state.facilitators.foldLeft(state.facilitators.map(_ -> initialVotes).toMap) { (acc, facilitator) =>
            resources.acksMap
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

          val keepThreshold = (state.facilitators.size + 1) / 2
          val removeThreshold = state.facilitators.size / 2 + 1

          state.facilitators.traverse { peerId =>
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
              state.copy(
                lockStatus = Reopened,
                facilitators = keptFacilitators,
                removedFacilitators = state.removedFacilitators.union(removedFacilitators.toSet)
              )
          }
        }.getOrElse(state)
      }
    }

}
