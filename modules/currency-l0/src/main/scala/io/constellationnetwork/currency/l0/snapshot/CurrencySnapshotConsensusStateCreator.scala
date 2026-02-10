package io.constellationnetwork.currency.l0.snapshot

import cats.effect.kernel.Clock
import cats.effect.{Async, Sync}
import cats.syntax.all._

import io.constellationnetwork.currency.l0.snapshot.schema.{CollectingFacilities, CurrencyConsensusKind, CurrencyConsensusOutcome}
import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Facility
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.consensus.state.{ConsensusStateCreator, _}
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class CurrencySnapshotConsensusStateCreator[F[_]: Sync]
    extends ConsensusStateCreator[
      F,
      CurrencySnapshotKey,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      CurrencySnapshotStatus,
      CurrencyConsensusOutcome,
      CurrencyConsensusKind
    ]

object CurrencySnapshotConsensusStateCreator {

  def make[F[_]: Async](
    consensusFns: CurrencySnapshotConsensusFunctions[F],
    consensusStorage: CurrencyConsensusStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    gossip: Gossip[F],
    selfId: PeerId,
    seedlist: Option[Set[SeedlistEntry]],
    facilitatorSelector: FacilitatorSelector
  ): CurrencySnapshotConsensusStateCreator[F] = new CurrencySnapshotConsensusStateCreator[F] {

    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

    def tryFacilitateConsensus(
      key: CurrencySnapshotKey,
      lastOutcome: CurrencyConsensusOutcome,
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
    ): F[StateCreateResult] =
      consensusStorage
        .condModifyState(key)(toCreateStateFn(facilitateConsensus(key, lastOutcome, maybeTrigger, resources)))
        .flatMap(evalEffect)
        .flatTap(logIfCreated)

    private def facilitateConsensus(
      key: CurrencySnapshotKey,
      lastOutcome: CurrencyConsensusOutcome,
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
    ): F[(CurrencySnapshotConsensusState, F[Unit])] =
      for {
        candidates <- consensusStorage.getCandidates(key.next)
        previousEligible = lastOutcome.eligibleOrFacilitators
        approvedCandidates = lastOutcome.finished.candidates.value
        seedlistPeerIds = seedlist.map(_.map(_.peerId)).getOrElse(Set.empty)

        filteredPreviousEligible = previousEligible
          .filter(peerId => seedlist.isEmpty || seedlistPeerIds.contains(peerId))

        filteredCandidates = approvedCandidates
          .filter(peerId => seedlist.isEmpty || seedlistPeerIds.contains(peerId))

        // Exclude peers that were removed by unlock consensus in the previous round.
        // This is deterministic: all nodes agreed on removedFacilitators via majority vote.
        previouslyRemoved = lastOutcome.removedFacilitators.value

        baseFacilitators = (filteredPreviousEligible ++ filteredCandidates).distinct
          .filterNot(previouslyRemoved.contains)

        _ <- logger.debug(
          s"Facilitator selection for key=$key: " +
            s"previousEligible=${filteredPreviousEligible.size}, " +
            s"candidates=${filteredCandidates.size}, " +
            s"base=${baseFacilitators.size}" +
            (if (previouslyRemoved.nonEmpty) s", excludedFromPreviousRound=${previouslyRemoved.size}" else "")
        )

        // Full eligible set after collateral filtering
        eligible <- (baseFacilitators :+ selfId).distinct
          .filterA(
            consensusFns.facilitatorFilter(
              lastOutcome.finished.signedMajorityArtifact,
              lastOutcome.finished.context,
              _
            )
          )
          .map { list =>
            if (list.isEmpty) List(selfId) else list
          }

        filteredOutByCollateral = baseFacilitators.filterNot(eligible.contains)
        _ <- filteredOutByCollateral.traverse_ { peerId =>
          logger.debug(s"Facilitator ${peerId.show} removed by facilitatorFilter for key=$key")
        }

        // Apply deterministic subset selection using hash-distance ordering
        // Uses the previous round's snapshot hash as entropy for randomization
        entropy = lastOutcome.finished.snapshotHash
        activeFacilitators = facilitatorSelector.select(eligible, entropy)

        _ <- logger
          .info(
            s"Facilitator subsetting for key=$key: " +
              s"eligible=${eligible.size}, selected=${activeFacilitators.size}"
          )
          .whenA(activeFacilitators.size < eligible.size)

        (withdrawn, active) = activeFacilitators.partition { peerId =>
          resources.withdrawalsMap.get(peerId).contains(CurrencyConsensusKind.Facility)
        }
        _ <- withdrawn.traverse_ { peerId =>
          logger.info(s"Facilitator ${peerId.show} has withdrawn from consensus at key=$key")
        }
        time <- Clock[F].monotonic
        lastGlobalSnapshotOrdinal <- lastGlobalSnapshotStorage.getOrdinal.map(_.getOrElse(SnapshotOrdinal.MinValue))

        effect = consensusStorage.getUpperBound.flatMap { bound =>
          gossip.spread(
            ConsensusPeerDeclaration(
              key,
              Facility(
                bound,
                candidates,
                maybeTrigger,
                lastOutcome.finished.facilitatorsHash,
                lastGlobalSnapshotOrdinal,
                lastOutcome.finished.snapshotHash
              )
            )
          )
        }

        state = ConsensusState[CurrencySnapshotKey, CurrencySnapshotStatus, CurrencyConsensusOutcome, CurrencyConsensusKind](
          key,
          lastOutcome,
          Facilitators(active),
          CollectingFacilities(
            maybeTrigger,
            lastOutcome.finished.facilitatorsHash,
            lastOutcome.finished.snapshotHash
          ),
          time,
          withdrawnFacilitators = WithdrawnFacilitators(withdrawn.toSet),
          spreadAckKinds = Set.empty,
          eligibleFacilitators = EligibleFacilitators(eligible)
        )

        _ <- logger.info(
          s"Created consensus state for key=$key with ${active.size} active facilitators" +
            s" (${eligible.size} eligible)" +
            withdrawn.headOption.fold("")(_ => s", ${withdrawn.size} withdrawn")
        )

      } yield (state, effect)
  }
}
