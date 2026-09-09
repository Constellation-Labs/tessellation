package io.constellationnetwork.currency.l0.snapshot.synchronous.update

import cats.Monad
import cats.data.StateT
import cats.syntax.all._

import io.constellationnetwork.currency.l0.snapshot.synchronous.{Facilitators, LockStatus, RemovedFacilitators}
import io.constellationnetwork.schema.peer.PeerId

import monocle.Lens

/** Exact release/mainnet ACK decision rule.
  *
  * A peer is kept with at least `(n + 1) / 2` keep votes and removed only with at least `n / 2 + 1` remove votes. An inconclusive peer
  * keeps the phase locked; the implementation never guesses a smaller committee.
  */
object UnlockConsensusUpdate {

  def tryUnlock[F[_]: Monad, S, K](acksMap: Map[(PeerId, K), Set[PeerId]])(maybeCollectingKind: S => Option[K])(
    implicit lockStatus: Lens[S, LockStatus],
    facilitators: Lens[S, Facilitators],
    removedFacilitators: Lens[S, RemovedFacilitators]
  ): StateT[F, S, Unit] =
    StateT.modify { state =>
      if (lockStatus.get(state) =!= LockStatus.Closed) state
      else {
        val (voteKeep, voteRemove, initialVotes) = ((1, 0), (0, 1), (0, 0))

        maybeCollectingKind(state).flatMap { collectingKind =>
          val votingResult = facilitators
            .get(state)
            .value
            .foldLeft(
              facilitators.get(state).value.map(_ -> initialVotes).toMap
            ) { (acc, facilitator) =>
              acksMap.get((facilitator, collectingKind)).fold(acc) { ack =>
                acc.map {
                  case (peerId, votes) =>
                    if (ack.contains(peerId)) peerId -> (votes |+| voteKeep)
                    else peerId -> (votes |+| voteRemove)
                }
              }
            }

          val keepThreshold = (facilitators.get(state).value.size + 1) / 2
          val removeThreshold = facilitators.get(state).value.size / 2 + 1

          facilitators
            .get(state)
            .value
            .traverse { peerId =>
              votingResult.get(peerId).flatMap {
                case (kept, _) if kept >= keepThreshold         => (peerId, true).some
                case (_, removed) if removed >= removeThreshold => (peerId, false).some
                case _                                          => none
              }
            }
            .map { decisions =>
              val (removed, kept) = decisions.partitionMap {
                case (peerId, decision) => Either.cond(decision, peerId, peerId)
              }
              lockStatus
                .replace(LockStatus.Reopened)
                .andThen(facilitators.replace(Facilitators(kept)))
                .andThen(removedFacilitators.modify(r => RemovedFacilitators(r.value.union(removed.toSet))))(state)
            }
        }.getOrElse(state)
      }
    }
}
