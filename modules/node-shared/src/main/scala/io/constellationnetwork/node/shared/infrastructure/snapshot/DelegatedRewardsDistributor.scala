package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.Applicative
import cats.data.NonEmptySet
import cats.effect.{Async, Sync}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet, TreeSet}

import io.constellationnetwork.currency.dataApplication.DataCalculatedState
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceResult
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.{DelegatedStakeRewardParameters, RewardFraction, UpdateNodeParameters}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.transaction.{RewardTransaction, Transaction, TransactionAmount}
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.syntax.sortedCollection.{sortedMapSyntax, sortedSetSyntax}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}

case class DelegatedRewardsResult(
  delegatorRewardsMap: SortedMap[PeerId, SortedMap[Address, Amount]],
  updatedCreateDelegatedStakes: SortedMap[Address, SortedSet[DelegatedStakeRecord]],
  updatedWithdrawDelegatedStakes: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
  nodeOperatorRewards: SortedSet[RewardTransaction],
  reservedAddressRewards: SortedSet[RewardTransaction],
  withdrawalRewardTxs: SortedSet[RewardTransaction],
  totalEmittedRewardsAmount: Amount
)

case class PartitionedStakeUpdates(
  unexpiredCreateDelegatedStakes: SortedMap[Address, SortedSet[DelegatedStakeRecord]],
  unexpiredWithdrawalsDelegatedStaking: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
  expiredWithdrawalsDelegatedStaking: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]]
)

trait DelegatedRewardsDistributor[F[_]] {

  def getEmissionConfig(epochProgress: EpochProgress): F[EmissionConfigEntry]

  def calculateVariableInflation(epochProgress: EpochProgress, lastSnapshotContext: GlobalSnapshotInfo): F[Amount]

  def distribute(
    lastSnapshotContext: GlobalSnapshotInfo,
    trigger: ConsensusTrigger,
    epochProgress: EpochProgress,
    facilitators: List[(Address, PeerId)],
    delegatedStakeDiffs: UpdateDelegatedStakeAcceptanceResult,
    partitionedRecords: PartitionedStakeUpdates
  ): F[DelegatedRewardsResult]
}

object DelegatedRewardsDistributor {

  /** Identifies which stakes are being modified (have matching tokenLockRef in both existing records and acceptedCreates). Returns a Set of
    * (Address, TokenLockRef) tuples representing the modified stakes.
    *
    * Matching uses the existing record's effective `tokenLockRef` (the replacement `currentTokenLockRef` when present, otherwise the
    * original `event.tokenLockRef`) so that a Create which re-delegates a stake whose token lock has been replaced is still recognized as a
    * modification of that stake rather than treated as a brand-new one.
    */
  def identifyModifiedStakes(
    existingRecords: SortedMap[Address, SortedSet[DelegatedStakeRecord]],
    acceptedCreates: SortedMap[Address, List[(Signed[UpdateDelegatedStake.Create], SnapshotOrdinal)]]
  ): Set[(Address, Hash)] =
    acceptedCreates.toSeq.flatMap {
      case (addr, creates) =>
        creates.flatMap {
          case (ev, _) =>
            existingRecords
              .get(addr)
              .filter(_.exists(_.tokenLockRef === ev.tokenLockRef))
              .map(_ => addr -> ev.tokenLockRef)
        }
    }.toSet

  /** Returns a filtered version of existingRecords with modified stakes removed. This is used to exclude modified stakes from reward
    * calculations.
    */
  def filterOutModifiedStakes(
    existingRecords: SortedMap[Address, SortedSet[DelegatedStakeRecord]],
    modifiedStakes: Set[(Address, Hash)]
  ): SortedMap[Address, SortedSet[DelegatedStakeRecord]] =
    existingRecords.iterator.flatMap {
      case (address, records) =>
        val filtered = records.filterNot { record =>
          modifiedStakes.contains(
            (address, record.tokenLockRef)
          )
        }

        if (filtered.nonEmpty) Some(address -> filtered)
        else None
    }.toSortedMap

  def getUpdatedCreateDelegatedStakes[F[_]: Async: Hasher](
    delegatorRewardsMap: SortedMap[PeerId, SortedMap[Address, Amount]],
    delegatedStakeDiffs: UpdateDelegatedStakeAcceptanceResult,
    partitionedRecords: PartitionedStakeUpdates
  ): F[SortedMap[Address, SortedSet[DelegatedStakeRecord]]] = {
    val existingRecords = partitionedRecords.unexpiredCreateDelegatedStakes
    val modifiedStakes = identifyModifiedStakes(existingRecords, delegatedStakeDiffs.acceptedCreates)

    for {
      newRecordsWithRewards <- delegatedStakeDiffs.acceptedCreates.toList.traverse {
        case (addr, stakeList) =>
          val existingRecordsForAddr = existingRecords.getOrElse(addr, List.empty)

          stakeList.traverse {
            case (ev, ord) =>
              // Match on the existing record's effective tokenLockRef (currentTokenLockRef when the lock has been
              // replaced, otherwise event.tokenLockRef) so accumulated rewards carry over to a re-delegated/increased
              // stake instead of resetting to Balance.empty.
              val matchingExistingRecord = existingRecordsForAddr.find { record =>
                record.tokenLockRef === ev.tokenLockRef
              }

              DelegatedStakeRecord(
                ev,
                ord,
                matchingExistingRecord.map(_.rewards).getOrElse(Balance.empty),
                matchingExistingRecord.flatMap(_.currentTokenLockRef),
                matchingExistingRecord.flatMap(_.currentAmount)
              ).pure[F]
          }.map(records => addr -> records.toSortedSet)
      }.map(_.toSortedMap)

      filteredExistingRecords = filterOutModifiedStakes(existingRecords, modifiedStakes)

      mergedRecords = filteredExistingRecords |+| newRecordsWithRewards

      activeStakes <- {
        val withdrawnStakes = delegatedStakeDiffs.acceptedWithdrawals.flatMap(_._2.map(_._1.stakeRef)).toSet

        mergedRecords.toList.traverse {
          case (addr, records) =>
            records.toList.traverse { record =>
              for {
                ref <- DelegatedStakeReference.of[F](record.event)
                isWithdrawn = withdrawnStakes.contains(ref.hash)
              } yield (record, isWithdrawn)
            }.map { recordsWithWithdrawnFlag =>
              val keptRecords = recordsWithWithdrawnFlag
                .filterNot(_._2) // Remove records that are withdrawn
                .map(_._1) // Get just the records without the flags

              (addr, keptRecords)
            }
        }.map(_.toMap)
      }.map(_.map {
        case (addr, recs) =>
          addr -> recs.map { record =>
            val isModified = modifiedStakes.contains((addr, record.event.tokenLockRef))

            val nodeSpecificReward =
              if (isModified) Amount.empty
              else
                delegatorRewardsMap
                  .get(record.event.nodeId)
                  .flatMap(_.get(addr))
                  .getOrElse(Amount.empty)

            // ensure we're not accidentally zeroing out rewards
            val disbursedBalance =
              if (isModified) record.rewards
              else record.rewards.plus(nodeSpecificReward).toOption.getOrElse(Amount.empty)

            DelegatedStakeRecord(record.event, record.createdAt, disbursedBalance, record.currentTokenLockRef, record.currentAmount)
          }.toSortedSet
      }.filterNot(_._2.isEmpty).toSortedMap)
    } yield activeStakes
  }

  def getUpdatedWithdrawalDelegatedStakes[F[_]: Async: Hasher](
    lastSnapshotContext: GlobalSnapshotInfo,
    delegatedStakeDiffs: UpdateDelegatedStakeAcceptanceResult,
    partitionedRecords: PartitionedStakeUpdates
  ): F[SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]]] =
    delegatedStakeDiffs.acceptedWithdrawals.toList.traverse {
      case (addr, acceptedWithdrawls) =>
        acceptedWithdrawls.traverse {
          case (ev, ep) =>
            lastSnapshotContext.activeDelegatedStakes
              .flatTraverse(_.get(addr).flatTraverse {
                _.findM { s =>
                  DelegatedStakeReference.of(s.event).map(_.hash === ev.stakeRef)
                }.map(
                  _.map(rec =>
                    PendingDelegatedStakeWithdrawal(rec.event, rec.rewards, rec.createdAt, ep, rec.currentTokenLockRef, rec.currentAmount)
                  )
                )
              })
              .flatMap(Async[F].fromOption(_, new RuntimeException("Unexpected None when processing user delegations")))
        }.map { records =>
          addr -> records.toSortedSet
        }
    }.map(records => SortedMap.from(records))
      .map(partitionedRecords.unexpiredWithdrawalsDelegatedStaking |+| _)
      .map(_.filterNot(_._2.isEmpty))

  def sumMintedAmount[F[_]: Async](
    reservedAddressRewards: SortedSet[RewardTransaction],
    nodeOperatorRewards: SortedSet[RewardTransaction],
    delegatorRewardsMap: SortedMap[PeerId, SortedMap[Address, Amount]]
  ): F[Amount] = {
    val reservedEmittedAmount = reservedAddressRewards.toList.map(_.amount.value.value).sum
    val validatorsEmittedAmount = nodeOperatorRewards.toList.map(_.amount.value.value).sum
    val delegatorsEmittedAmount = delegatorRewardsMap.map(_._2.values.map(_.value.value).sum).sum
    val totalEmitted = reservedEmittedAmount + validatorsEmittedAmount + delegatorsEmittedAmount
    NonNegLong
      .from(totalEmitted)
      .bimap(
        new IllegalArgumentException(_),
        Amount(_)
      )
      .pure[F]
      .flatMap(Async[F].fromEither(_))
  }
}
