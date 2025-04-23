package io.constellationnetwork.dag.l0.infrastructure.rewards

import java.math.MathContext

import cats.effect.{Async, Sync}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.math.BigDecimal.RoundingMode

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceResult
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeRecord, UpdateDelegatedStake}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.{DelegatedStakeRewardParameters, RewardFraction, UpdateNodeParameters}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.transaction.{RewardTransaction, TransactionAmount}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
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

    // Define a high precision MathContext for consistent calculations
    val mc: MathContext = new java.math.MathContext(24, java.math.RoundingMode.HALF_UP)

    /** Calculate the variable amount of rewards to mint for this epoch based on the config and epoch progress.
      */
    def calculateVariableInflation(epochProgress: EpochProgress): F[Amount] = for {
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
    ): F[DelegatedRewardsResult] =
      trigger match {
        case EventTrigger =>
          applyDistribution(
            lastSnapshotContext,
            epochProgress,
            facilitators,
            delegatedStakeDiffs,
            partitionedRecords,
            List.empty,
            BigDecimal(0L, mc),
            BigDecimal(0L, mc)
          )
        case TimeTrigger =>
          for {
            emitFromFunction <- calculateVariableInflation(epochProgress)
            pctConfig <- delegatedRewardsConfig.percentDistribution
              .get(environment)
              .pure[F]
              .flatMap(
                Async[F]
                  .fromOption(_, new RuntimeException(s"Could not retrieve program distribution config for env: $environment"))
              )
              .map(f => f(epochProgress))
            epochSPerYear <- delegatedRewardsConfig.emissionConfig
              .get(environment)
              .map(_.epochsPerYear.value)
              .pure[F]
              .flatMap(Async[F].fromOption(_, new RuntimeException(s"Could not retrieve emission config for env: $environment")))
            (reservedRewards, facilitatorRewardPool, delegatorRewardPool) <- calculateEmissionDistribution(
              lastSnapshotContext.activeDelegatedStakes.getOrElse(SortedMap.empty),
              emitFromFunction,
              pctConfig,
              epochSPerYear
            )
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
      activeDelegatedStakes: SortedMap[Address, List[DelegatedStakeRecord]],
      totalRewards: Amount,
      pctConfig: ProgramsDistributionConfig,
      epochsPerYear: Long
    ): F[(List[(Address, BigDecimal)], BigDecimal, BigDecimal)] =
      getTotalActiveStake(activeDelegatedStakes).map { totalStakedAmount =>
        val reservedAddressWeights = pctConfig.weights.toList.map {
          case (addr, pct) => addr -> pct.toBigDecimal
        }
        val reservedWeight = reservedAddressWeights.map(_._2).sum
        val validatorsWeight = pctConfig.validatorsWeight.toBigDecimal
        val delegatorsWeight = pctConfig.delegatorsWeight.toBigDecimal
        val totalWeight = reservedWeight + validatorsWeight + delegatorsWeight

        val annualFlatInflationRate = delegatedRewardsConfig.flatInflationRate.toBigDecimal

        val reservedPercentage = reservedWeight / totalWeight
        val validatorsPercentage = validatorsWeight / totalWeight
        val delegatorsPercentage = delegatorsWeight / totalWeight

        // these values are in datum
        val variableRewardsBD = BigDecimal(totalRewards.value.value, mc)
        val totalStaked = BigDecimal(totalStakedAmount.value.value, mc)

        // Calculate individual portions from the variable amount
        val reservedTotal = variableRewardsBD * reservedPercentage
        val staticValidatorRewardPool = variableRewardsBD * validatorsPercentage
        val baseDelegationRewardPool = variableRewardsBD * delegatorsPercentage

        // Calculate inflation amount (applied only to delegators)
        val annualEmissionValue = totalStaked * annualFlatInflationRate
        val flatInflationRewardPool = annualEmissionValue / BigDecimal(epochsPerYear, mc)

        // Total delegation pool includes base amount plus all inflation
        val totalDelegationRewardPool = baseDelegationRewardPool + flatInflationRewardPool

        // Calculate reserved rewards for each address based on their proportion of reserved weight
        val reservedAddressRewards =
          if (variableRewardsBD <= 0 || reservedWeight <= 0) List.empty
          else {
            reservedAddressWeights.map {
              case (addr, pct) =>
                val proportion = pct / reservedWeight
                val reward = proportion * reservedTotal
                addr -> reward
            }
          }

        (reservedAddressRewards, staticValidatorRewardPool, totalDelegationRewardPool)
      }

    /** Calculate rewards using the emission formula with deterministic precision: i(t) = i_initial + (i_initial - i_target) * e^{ -λ *
      * (Y_current - Y_initial) * (P_initial / P_current)^{i_impact} }
      */
    private def calculateEmissionRewards(
      epochProgress: EpochProgress,
      emConfig: EmissionConfigEntry
    ): F[Amount] = Sync[F].defer {
      val iTarget = emConfig.iTarget.toBigDecimal
      val iInitial = emConfig.iInitial.toBigDecimal
      val lambda = emConfig.lambda.toBigDecimal
      val iImpact = emConfig.iImpact.toBigDecimal
      val epochsPerYear = BigDecimal(emConfig.epochsPerYear.value, mc)
      val transitionEpoch = BigDecimal(emConfig.asOfEpoch.value.value, mc)
      val totalSupply = BigDecimal(emConfig.totalSupply.value, mc)

      if (emConfig.dagPrices.values.isEmpty) {
        Slf4jLogger.getLogger[F].error("Empty DAG price configuration").as(Amount.empty)
      } else {
        val dagPrices = emConfig.dagPrices
        val initialPrice = dagPrices.head._2.toBigDecimal
        val currentPrice = getCurrentDagPrice(epochProgress, dagPrices).toBigDecimal
        val yearDiff = BigDecimal(epochProgress.value.value - transitionEpoch.toLong, mc) / epochsPerYear

        for {
          // Current year
          currentYearFraction <- yearDiff.pure[F]

          // Price ratio
          priceRatio = if (currentPrice <= 0) BigDecimal(0, mc) else initialPrice / currentPrice

          // Price impact
          priceImpactValue = BigDecimal(Math.pow(priceRatio.toDouble, iImpact.toDouble), mc)

          // Lambda * year difference
          lambdaTimesTDiff = lambda * currentYearFraction

          // exp(-lambdaTimesTDiff * priceImpactValue)
          expArgValue = -lambdaTimesTDiff * priceImpactValue
          expTimesPriceImpact = BigDecimal(Math.exp(expArgValue.toDouble), mc)

          // Calculate inflation rate components
          iInitialMinusTarget <- (iInitial - iTarget).pure[F]
          diffTerm = iInitialMinusTarget * expTimesPriceImpact
          uncappedInflationRate = iTarget + diffTerm

          // Cap annual inflation at 6%
          maxInflation = BigDecimal("0.06", mc)
          annualInflationRate = if (uncappedInflationRate > maxInflation) maxInflation else uncappedInflationRate

          // Annual emission calculation
          annualEmissionValue = totalSupply * annualInflationRate

          // Per epoch emission
          perEpochEmissionValue = annualEmissionValue / epochsPerYear

          // Convert to Amount with consistent rounding
          emissionLong = perEpochEmissionValue.setScale(0, RoundingMode.HALF_UP).toLong

          amount <- NonNegLong
            .from(emissionLong)
            .pure[F]
            .map(_.leftMap(new IllegalArgumentException(_)))
            .flatMap(Async[F].fromEither(_))
            .map(Amount(_))

        } yield amount
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

    private def getStakedAmount(stakeRecord: DelegatedStakeRecord): Long =
      stakeRecord.event.value.amount.value.value + stakeRecord.rewards.value

    private def getTotalActiveStake(
      activeDelegatedStakes: SortedMap[Address, List[DelegatedStakeRecord]]
    ): F[Amount] =
      if (activeDelegatedStakes.isEmpty) Amount.empty.pure[F]
      else {
        val activeStakes = activeDelegatedStakes.flatMap {
          case (address, records) =>
            records.map { record =>
              (record.event.value.nodeId.toId, address, record)
            }
        }

        NonNegLong
          .from(activeStakes.map(s => getStakedAmount(s._3)).sum)
          .pure[F]
          .map(_.leftMap(new IllegalArgumentException(_)))
          .flatMap(Async[F].fromEither(_))
          .map(Amount(_))
      }

    private def calculateDelegatorRewards(
      activeDelegatedStakes: SortedMap[Address, List[DelegatedStakeRecord]],
      nodeParametersMap: SortedMap[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)],
      totalDelegationRewardPool: BigDecimal,
      acceptedCreates: SortedMap[Address, List[(Signed[UpdateDelegatedStake.Create], SnapshotOrdinal)]]
    ): F[Map[PeerId, Map[Address, Amount]]] =
      if (totalDelegationRewardPool === BigDecimal(0.0)) Map.empty[PeerId, Map[Address, Amount]].pure[F]
      else {
        val modifiedStakes = DelegatedRewardsDistributor.identifyModifiedStakes(activeDelegatedStakes, acceptedCreates)
        val filteredActiveStakes = DelegatedRewardsDistributor.filterOutModifiedStakes(activeDelegatedStakes, modifiedStakes)

        val adjustedActiveStakes = filteredActiveStakes.flatMap {
          case (address, records) =>
            records.map { record =>
              (record.event.value.nodeId.toId, address, record)
            }
        }

        getTotalActiveStake(filteredActiveStakes).flatMap { totalStakeAmount =>
          val totalStakeBD = BigDecimal(totalStakeAmount.value.value, mc)

          if (activeDelegatedStakes.isEmpty || totalStakeBD === 0) Map.empty[PeerId, Map[Address, Amount]].pure[F]
          else {
            adjustedActiveStakes
              .groupBy(_._1)
              .toList
              .flatTraverse {
                case (nodeId, nodeStakes) =>
                  for {
                    nodeStakeAmount <- nodeStakes.map(t => BigDecimal(getStakedAmount(t._3), mc)).sum.pure[F]
                    nodePortionOfTotalStake = if (totalStakeBD > 0) nodeStakeAmount / totalStakeBD else BigDecimal(0, mc)

                    nodeRewardParams = nodeParametersMap
                      .get(nodeId)
                      .map(_._1.value.delegatedStakeRewardParameters)
                      .getOrElse(DelegatedStakeRewardParameters(RewardFraction.unsafeFrom(0)))

                    // Convert from RewardFraction (0-100M) to BigDecimal percentage (0-1)
                    // RewardFraction is operator cut, so delegators get (1 - operatorFraction)
                    operatorFraction = BigDecimal(nodeRewardParams.rewardFraction, mc) / BigDecimal(100_000_000, mc)
                    delegatorRewardPercentage = BigDecimal(1, mc) - operatorFraction

                    _ <-
                      if (delegatorRewardPercentage < 0)
                        Async[F].raiseError(new RuntimeException("Unexpected delegate rewards percentage. Got value less than zero."))
                      else Async[F].unit

                    delegatorStakes = nodeStakes.groupBy(_._2).map {
                      case (address, stakeTuples) => (address, stakeTuples.map(_._3))
                    }

                    addressRewards <- delegatorStakes.toList.traverse {
                      case (address, stakes) =>
                        val delegatorStakeAmountTotal = stakes.map(s => BigDecimal(getStakedAmount(s), mc)).sum
                        val delegatorPortionOfNodeStake =
                          if (nodeStakeAmount <= 0) BigDecimal(0, mc)
                          else delegatorStakeAmountTotal / nodeStakeAmount

                        val rewardBD = totalDelegationRewardPool *
                          delegatorRewardPercentage *
                          nodePortionOfTotalStake *
                          delegatorPortionOfNodeStake

                        val rewardLong = rewardBD.setScale(0, RoundingMode.HALF_UP).toLong

                        NonNegLong
                          .from(rewardLong)
                          .pure[F]
                          .map(_.leftMap(new IllegalArgumentException(_)))
                          .flatMap(Async[F].fromEither(_))
                          .map(Amount(_))
                          .map(rewardAmount => nodeId.toPeerId -> (address -> rewardAmount))
                    }
                  } yield addressRewards
              }
              .map(_.groupBy(_._1).view.mapValues(_.map(_._2).toMap).toMap)
          }
        }
      }

    private def calculateNodeOperatorRewards(
      nodeParametersMap: SortedMap[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)],
      facilitators: List[(Address, PeerId)],
      facilitatorRewardPool: BigDecimal,
      delegatorRewardPool: BigDecimal,
      lastSnapshotContext: GlobalSnapshotInfo,
      acceptedCreates: SortedMap[Address, List[(Signed[UpdateDelegatedStake.Create], SnapshotOrdinal)]] = SortedMap.empty
    ): F[SortedSet[RewardTransaction]] = {
      val facilitatorCount = BigDecimal(facilitators.size, mc)
      val perValidatorStaticReward =
        if (facilitators.isEmpty) BigDecimal(0, mc)
        else facilitatorRewardPool / facilitatorCount

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
        val modifiedStakes = DelegatedRewardsDistributor.identifyModifiedStakes(activeDelegatedStakes, acceptedCreates)
        val filteredActiveDelegatedStakes = DelegatedRewardsDistributor.filterOutModifiedStakes(activeDelegatedStakes, modifiedStakes)
        val nodeStakes = filteredActiveDelegatedStakes.values.flatten
          .groupBy(_.event.value.nodeId.toId)
          .view
          .mapValues(stakes => stakes.map(s => BigDecimal(getStakedAmount(s), mc)).sum)
          .toMap

        val facilitatorStakes = facilitators.map {
          case (_, id) =>
            (id, nodeStakes.getOrElse(id.toId, BigDecimal(0, mc)))
        }

        val totalFacilitatorStake = facilitatorStakes.map(_._2).sum

        val dynamicRewardsList =
          if (totalFacilitatorStake <= 0) List.empty
          else {
            facilitatorStakes.flatMap {
              case (nodeId, stakeAmount) =>
                if (stakeAmount <= 0) None
                else {
                  nodeParametersMap.get(nodeId.toId).flatMap {
                    case (params, _) =>
                      // RewardFraction is stored as a value from 0 to 100,000,000 representing 0% to 100%
                      val operatorCommission = BigDecimal(params.value.delegatedStakeRewardParameters.rewardFraction, mc) /
                        BigDecimal(100_000_000, mc)

                      val stakeRatio = stakeAmount / totalFacilitatorStake
                      val dynamicRewardBD = delegatorRewardPool * stakeRatio * operatorCommission
                      val dynamicReward = dynamicRewardBD.setScale(0, RoundingMode.HALF_UP).toLong

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
      withdrawingBalances.toList.filter { case (_, amount) => amount.value.value > 0 }.traverse {
        case (address, amount) =>
          PosLong
            .from(amount.value.value)
            .pure[F]
            .map(_.leftMap(new IllegalArgumentException(_)))
            .flatMap(Async[F].fromEither(_))
            .map(TransactionAmount(_))
            .map(txAmt =>
              RewardTransaction(
                address,
                txAmt
              )
            )
      }
        .map(SortedSet.from(_))

    private def applyDistribution(
      lastSnapshotContext: GlobalSnapshotInfo,
      epochProgress: EpochProgress,
      facilitators: List[(Address, PeerId)],
      delegatedStakeDiffs: UpdateDelegatedStakeAcceptanceResult,
      partitionedRecords: PartitionedStakeUpdates,
      reservedRewardDistribution: List[(Address, BigDecimal)],
      facilitatorRewardPool: BigDecimal,
      delegatorRewardPool: BigDecimal
    ): F[DelegatedRewardsResult] =
      for {
        delegatorRewardsMap <-
          calculateDelegatorRewards(
            lastSnapshotContext.activeDelegatedStakes.getOrElse(SortedMap.empty),
            lastSnapshotContext.updateNodeParameters.getOrElse(SortedMap.empty),
            delegatorRewardPool,
            delegatedStakeDiffs.acceptedCreates
          ).map(_.toSortedMap)

        nodeOperatorRewards <-
          calculateNodeOperatorRewards(
            lastSnapshotContext.updateNodeParameters.getOrElse(SortedMap.empty),
            facilitators,
            facilitatorRewardPool,
            delegatorRewardPool,
            lastSnapshotContext,
            delegatedStakeDiffs.acceptedCreates
          )

        reservedAddressRewards <-
          calculateReservedRewards(
            reservedRewardDistribution
          )

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
        DelegatedRewardsResult(
          delegatorRewardsMap,
          updatedCreateDelegatedStakes,
          updatedWithdrawDelegatedStakes,
          nodeOperatorRewards,
          reservedAddressRewards,
          withdrawalRewardTxs,
          totalEmittedReward
        )
  }
}
