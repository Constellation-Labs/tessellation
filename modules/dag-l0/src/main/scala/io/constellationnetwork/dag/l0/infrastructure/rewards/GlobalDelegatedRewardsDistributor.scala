package io.constellationnetwork.dag.l0.infrastructure.rewards

import cats.effect.{Async, Sync}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.math.BigDecimal.RoundingMode

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceResult
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.snapshot.{
  DelegatedRewardsDistributor,
  DelegationRewardsResult,
  PartitionedStakeUpdates
}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake.DelegatedStakeRecord
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.{DelegatedStakeRewardParameters, RewardFraction, UpdateNodeParameters}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.transaction.{RewardTransaction, TransactionAmount}
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.syntax.sortedCollection.sortedMapSyntax

import eu.timepit.refined.auto.autoUnwrap
import eu.timepit.refined.types.all.{NonNegLong, PosLong}
import org.typelevel.log4cats.slf4j.Slf4jLogger

object GlobalDelegatedRewardsDistributor {

  def make[F[_]: Async: Hasher](
    environment: AppEnvironment,
    delegatedRewardsConfig: DelegatedRewardsConfig
  ): DelegatedRewardsDistributor[F] = new DelegatedRewardsDistributor[F] {

    /** Calculate the total rewards to mint for this epoch based on appropriate rewards mechanism.
      */
    def calculateTotalRewardsToMint(epochProgress: EpochProgress): F[Amount] = for {
      emConfig <- delegatedRewardsConfig.emissionConfig
        .get(environment)
        .pure[F]
        .flatMap(Async[F].fromOption(_, new RuntimeException(s"Could not retrieve emission config for env: $environment")))
      result <- calculateEmissionRewards(epochProgress, emConfig)
    } yield result

    /** Implements the distribute method that encapsulates all reward calculation logic for a consensus cycle. This method replaces the
      * reward calculation functionality that was previously spread across the GlobalSnapshotAcceptanceManager.
      */
    def distribute(
      lastSnapshotContext: GlobalSnapshotInfo,
      trigger: ConsensusTrigger,
      epochProgress: EpochProgress,
      facilitators: List[(Address, PeerId)],
      delegatedStakeDiffs: UpdateDelegatedStakeAcceptanceResult,
      partitionedRecords: PartitionedStakeUpdates
    ): F[DelegationRewardsResult] = trigger match {
      case EventTrigger =>
        applyDistribution(
          lastSnapshotContext,
          epochProgress,
          facilitators,
          delegatedStakeDiffs,
          partitionedRecords,
          List.empty,
          BigDecimal(0L),
          BigDecimal(0L)
        )
      case TimeTrigger =>
        for {
          emitFromFunction <- calculateTotalRewardsToMint(epochProgress)
          pctConfig <- delegatedRewardsConfig.percentDistribution
            .get(environment)
            .pure[F]
            .flatMap(Async[F].fromOption(_, new RuntimeException(s"Could not retrieve program distribution config for env: $environment")))
          (reservedRewards, facilitatorRewardPool, delegatorRewardPool) <- calculateEmissionDistribution(emitFromFunction, pctConfig)
          result <- applyDistribution(
            lastSnapshotContext,
            epochProgress,
            facilitators,
            delegatedStakeDiffs,
            partitionedRecords,
            reservedRewards,
            facilitatorRewardPool,
            delegatorRewardPool
          )
        } yield result
    }

    private def calculateEmissionDistribution(
      totalRewards: Amount,
      pctConfig: ProgramsDistributionConfig
    ): F[(List[(Address, BigDecimal)], BigDecimal, BigDecimal)] = Async[F].delay {
      val reservedAddressWeights = pctConfig.weights.toList.map { case (addr, pct) => addr -> pct.toBigDecimal }

      val reservedWeight = reservedAddressWeights.map(_._2).sum
      val validatorsWeight = pctConfig.validatorsWeight.toBigDecimal
      val delegatorsWeight = pctConfig.delegatorsWeight.toBigDecimal
      val totalWeight = reservedWeight + validatorsWeight + delegatorsWeight

      val delegatorFlatInflationPercentage = delegatedRewardsConfig.flatInflationRate.toBigDecimal

      val reservedAddressRewards =
        if (totalRewards.value.value <= 0 || totalWeight <= 0) List.empty
        else reservedAddressWeights.map { case (addr, pct) => addr -> (pct * BigDecimal(totalRewards.value.value)) }

      val staticValidatorRewardPool =
        if (totalRewards.value.value <= 0 || totalWeight <= 0) BigDecimal(0)
        else BigDecimal(totalRewards.value.value) * validatorsWeight / totalWeight

      val totalDelegationRewardPool =
        if (totalRewards.value.value <= 0 || totalWeight <= 0) BigDecimal(0)
        else
          BigDecimal(totalRewards.value.value) *
            (1 + delegatorFlatInflationPercentage) *
            delegatorsWeight / totalWeight

      (reservedAddressRewards, staticValidatorRewardPool, totalDelegationRewardPool)
    }

    /** Calculate rewards using the new emission formula with deterministic precision: i(t) = i_initial + (i_initial - i_target) * e^{ -λ *
      * (Y_current - Y_initial) * (P_initial / P_current)^i_impact }
      */
    private def calculateEmissionRewards(
      epochProgress: EpochProgress,
      emConfig: EmissionConfigEntry
    ): F[Amount] = Sync[F].defer {
      val iTarget = emConfig.iTarget
      val iInitial = emConfig.iInitial
      val lambda = emConfig.lambda
      val epochsPerYear = emConfig.epochsPerYear.value
      val transitionEpoch = emConfig.asOfEpoch.value.value
      val totalSupply = emConfig.totalSupply.value
      val iImpact = emConfig.iImpact

      if (emConfig.dagPrices.values.isEmpty) {
        Slf4jLogger.getLogger[F].error("Empty DAG price configuration").as(Amount(NonNegLong.unsafeFrom(0L)))
      } else {
        val dagPrices = emConfig.dagPrices
        val initialPrice = dagPrices.head._2
        val currentPrice = getCurrentDagPrice(epochProgress, dagPrices)

        // Years calculation - time since the transition epoch
        val yearDiff = (epochProgress.value.value - transitionEpoch).toDouble / epochsPerYear.toDouble

        for {
          // Current year as offset from initial year
          currentYearFraction <- NonNegFraction.fromDouble[F](yearDiff)

          // Price ratio calculation
          priceRatio = initialPrice.toBigDecimal / currentPrice.toBigDecimal match {
            case ratio if ratio < 0 => 0.0
            case ratio              => ratio.toDouble
          }

          // Price impact: (P_initial / P_current)^i_impact
          priceImpactDouble = math.pow(priceRatio, iImpact.toBigDecimal.toDouble)

          // Lambda * year difference: -λ * (Y_current - Y_initial)
          lambdaTimesTDiff = lambda.toBigDecimal * currentYearFraction.toBigDecimal

          // exp( -λ * (Y_current - Y_initial) * (P_initial / P_current)^i_impact )
          // The negative sign ensures that as time progresses, inflation decreases
          // (all else being equal)
          expArgDouble = -lambdaTimesTDiff.toDouble * priceImpactDouble
          expTimesPriceImpact = math.exp(expArgDouble)

          // Calculate inflation rate components
          iInitialMinusTarget <- iInitial - iTarget
          diffTerm = iInitialMinusTarget.toBigDecimal * expTimesPriceImpact
          inflationRate = iTarget.toBigDecimal + diffTerm

          // Annual emission calculation
          annualEmissionValue = BigDecimal(totalSupply.value) * inflationRate

          // Per epoch emission
          perEpochEmissionValue = annualEmissionValue / epochsPerYear.toDouble

          // Convert to Amount, ensuring no negative values
          emissionLong = perEpochEmissionValue.setScale(0, RoundingMode.HALF_UP).toLong
          amount <- NonNegLong
            .from(math.max(0, emissionLong))
            .pure[F]
            .map(_.leftMap(new IllegalArgumentException(_)))
            .flatMap(Async[F].fromEither(_))
        } yield Amount(amount)
      }
    }

    private def getCurrentDagPrice(
      epochProgress: EpochProgress,
      dagPrices: Map[EpochProgress, NonNegFraction]
    ): NonNegFraction =
      dagPrices.filter { case (epoch, _) => epoch.value <= epochProgress.value }
        .maxByOption(_._1.value.value)
        .map(_._2)
        .getOrElse(dagPrices.head._2) // Default to initial price if no match found

    private def calculateDelegatorRewards(
      activeDelegatedStakes: SortedMap[Address, List[DelegatedStakeRecord]],
      nodeParametersMap: SortedMap[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)],
      totalDelegationRewardPool: BigDecimal
    ): F[Map[PeerId, Map[Address, Amount]]] =
      if (activeDelegatedStakes.isEmpty || totalDelegationRewardPool === BigDecimal(0)) Map.empty[PeerId, Map[Address, Amount]].pure[F]
      else {
        val activeStakes = activeDelegatedStakes.flatMap {
          case (address, records) =>
            records.map { record =>
              (record.event.value.nodeId.toId, address, record)
            }
        }
        val totalStakeAmount = BigDecimal(activeStakes.map(_._3.event.value.amount.value.value).sum)

        if (totalStakeAmount <= 0) Map.empty[PeerId, Map[Address, Amount]].pure[F]
        else {
          activeStakes
            .groupBy(_._1)
            .toList
            .flatTraverse {
              case (nodeId, nodeStakes) =>
                for {
                  // total stake amount for this node
                  nodeStakeAmount <- BigDecimal(nodeStakes.map(_._3.event.value.amount.value.value).sum).pure[F]

                  // node's portion of total network stake
                  nodePortionOfTotalStake = if (totalStakeAmount > 0) nodeStakeAmount / totalStakeAmount else BigDecimal(0)

                  nodeRewardParams = nodeParametersMap
                    .get(nodeId)
                    .map(_._1.value.delegatedStakeRewardParameters)
                    .getOrElse(DelegatedStakeRewardParameters(RewardFraction.unsafeFrom(0)))

                  delegatorRewardPercentage = BigDecimal(nodeRewardParams.reward)

                  delegatorStakes = nodeStakes.groupBy(_._2).map {
                    case (address, stakeTuples) => (address, stakeTuples.map(_._3))
                  }

                  addressRewards = delegatorStakes.toList.map {
                    case (address, stakes) =>
                      val delegatorStakeAmountToThisNode = BigDecimal(stakes.map(_.event.value.amount.value.value).sum)
                      val delegatorPortionOfNodeStake =
                        if (nodeStakeAmount <= 0) BigDecimal(0)
                        else delegatorStakeAmountToThisNode / nodeStakeAmount

                      // Calculate the final reward amount
                      val delegatorReward =
                        totalDelegationRewardPool *
                          delegatorRewardPercentage *
                          nodePortionOfTotalStake *
                          delegatorPortionOfNodeStake

                      nodeId.toPeerId -> (address -> Amount(NonNegLong.unsafeFrom(math.max(0, delegatorReward.toLong))))
                  }
                } yield addressRewards
            }
            .map(_.groupBy(_._1).view.mapValues(_.map(_._2).toMap).toMap)
        }
      }

    private def calculateNodeOperatorRewards(
      nodeParametersMap: SortedMap[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)],
      facilitators: List[(Address, PeerId)],
      facilitatorRewardPool: BigDecimal,
      delegatorRewardPool: BigDecimal,
      lastSnapshotContext: GlobalSnapshotInfo
    ): F[SortedSet[RewardTransaction]] = {
      // Calculate per-validator static rewards based on equal distribution among facilitators
      val perValidatorStaticReward =
        if (facilitators.isEmpty) BigDecimal(0)
        else facilitatorRewardPool / BigDecimal(facilitators.size)

      val staticRewardsList = facilitators.flatMap {
        case (addr, _) =>
          val staticReward = perValidatorStaticReward.setScale(0, RoundingMode.HALF_UP).toLong
          if (staticReward > 0)
            Some(
              RewardTransaction(
                addr,
                TransactionAmount(PosLong.unsafeFrom(staticReward))
              )
            )
          else None
      }

      val activeDelegatedStakes =
        lastSnapshotContext.activeDelegatedStakes.getOrElse(SortedMap.empty[Address, List[DelegatedStakeRecord]])

      if (activeDelegatedStakes.isEmpty || (delegatorRewardPool === BigDecimal(0) && facilitatorRewardPool === BigDecimal(0)))
        SortedSet.from(staticRewardsList).pure[F]
      else {
        val nodeStakes = activeDelegatedStakes.values.flatten
          .groupBy(_.event.value.nodeId.toId)
          .view
          .mapValues(_.map(_.event.value.amount.value.value).sum)
          .toMap

        val facilitatorStakes = facilitators.map {
          case (_, id) =>
            (id, nodeStakes.getOrElse(id.toId, 0L))
        }

        val totalFacilitatorStake = facilitatorStakes.map(_._2).sum

        // Dynamic rewards - distributed proportionally based on each operator's share of stake
        val dynamicRewardsList =
          if (totalFacilitatorStake <= 0) List.empty
          else {
            facilitatorStakes.flatMap {
              case (nodeId, stakeAmount) =>
                if (stakeAmount <= 0) None // Skip nodes with no stake
                else {
                  nodeParametersMap.get(nodeId.toId).flatMap {
                    case (params, _) =>
                      // Get the operator commission percentage (what the operator gets from delegator rewards)
                      val operatorCommission = BigDecimal(params.value.delegatedStakeRewardParameters.reward)

                      // Calculate stake proportion
                      val stakeRatio = BigDecimal(stakeAmount) / BigDecimal(totalFacilitatorStake)

                      // Calculate the dynamic reward based on stake proportion
                      // This is the node operator's commission of delegated rewards
                      val dynamicReward = (delegatorRewardPool * stakeRatio * operatorCommission)
                        .setScale(0, RoundingMode.HALF_UP)
                        .toLong

                      if (dynamicReward > 0)
                        Some(
                          RewardTransaction(
                            params.value.source,
                            TransactionAmount(PosLong.unsafeFrom(dynamicReward))
                          )
                        )
                      else None
                  }
                }
            }
          }

        // Combine static and dynamic rewards
        SortedSet.from(staticRewardsList ++ dynamicRewardsList).pure[F]
      }
    }

    private def calculateReservedRewards(
      reservedRewards: List[(Address, BigDecimal)]
    ): F[SortedSet[RewardTransaction]] =
      reservedRewards.traverse {
        case (addr, amt) =>
          PosLong
            .from(amt.setScale(0, RoundingMode.HALF_UP).toLong)
            .leftMap(new IllegalArgumentException(_))
            .pure[F]
            .flatMap(Async[F].fromEither)
            .map { posLongAmt =>
              RewardTransaction(
                addr,
                TransactionAmount(posLongAmt)
              )
            }
      }.map(SortedSet.from(_))

    private def calculateWithdrawalRewardTransactions(
      withdrawingBalances: Map[Address, Amount]
    ): F[SortedSet[RewardTransaction]] =
      SortedSet
        .from(withdrawingBalances.toList.filter { case (_, amount) => amount.value.value > 0 }.map {
          case (address, amount) =>
            RewardTransaction(
              address,
              TransactionAmount(PosLong.unsafeFrom(amount.value.value))
            )
        })
        .pure[F]

    private def applyDistribution(
      lastSnapshotContext: GlobalSnapshotInfo,
      epochProgress: EpochProgress,
      facilitators: List[(Address, PeerId)],
      delegatedStakeDiffs: UpdateDelegatedStakeAcceptanceResult,
      partitionedRecords: PartitionedStakeUpdates,
      reservedRewardDistribution: List[(Address, BigDecimal)],
      facilitatorRewardPool: BigDecimal,
      delegatorRewardPool: BigDecimal
    ): F[DelegationRewardsResult] =
      for {
        // Calculate rewards for delegates, operators, and reserved address
        delegatorRewardsMap <-
          calculateDelegatorRewards(
            lastSnapshotContext.activeDelegatedStakes.getOrElse(SortedMap.empty),
            lastSnapshotContext.updateNodeParameters.getOrElse(SortedMap.empty),
            delegatorRewardPool
          )

        nodeOperatorRewards <-
          calculateNodeOperatorRewards(
            lastSnapshotContext.updateNodeParameters.getOrElse(SortedMap.empty),
            facilitators,
            facilitatorRewardPool,
            delegatorRewardPool,
            lastSnapshotContext
          )

        reservedAddressRewards <-
          calculateReservedRewards(
            reservedRewardDistribution
          )

        // Calculate output values that will be integrated into consensus state / snapshot
        updatedCreateDelegatedStakes <- DelegatedRewardsDistributor.getUpdatedCreateDelegatedStakes(
          delegatorRewardsMap,
          delegatedStakeDiffs,
          partitionedRecords
        )

        updatedWithdrawDelegatedStakes <- DelegatedRewardsDistributor.getUpdatedWithdrawalDelegatedStakes(
          lastSnapshotContext,
          delegatedStakeDiffs,
          partitionedRecords
        )

        withdrawalRewardTxs <-
          calculateWithdrawalRewardTransactions(
            partitionedRecords.expiredWithdrawalsDelegatedStaking.toList.flatMap {
              case (address, withdrawals) =>
                withdrawals.mapFilter { withdrawal =>
                  Option.when(withdrawal.rewards.value > Balance.empty.value) {
                    (address, Amount(NonNegLong.unsafeFrom(withdrawal.rewards.value.value)))
                  }
                }
            }.toMap
          )

        totalEmittedReward <- DelegatedRewardsDistributor.sumMintedAmount(
          reservedAddressRewards,
          nodeOperatorRewards,
          delegatorRewardsMap
        )

      } yield
        DelegationRewardsResult(
          delegatorRewardsMap.toSortedMap,
          updatedCreateDelegatedStakes,
          updatedWithdrawDelegatedStakes,
          nodeOperatorRewards,
          reservedAddressRewards,
          withdrawalRewardTxs,
          totalEmittedReward
        )
  }
}
