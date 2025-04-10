package io.constellationnetwork.dag.l0.infrastructure.rewards

import cats.data.NonEmptySet
import cats.effect.{Async, Sync}
import cats.implicits.catsSyntaxOrder
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.math.BigDecimal
import scala.math.BigDecimal.{RoundingMode, double2bigDecimal}

import io.constellationnetwork.dag.l0.config.types.{AppConfig, MainnetRewardsConfig}
import io.constellationnetwork.dag.l0.config.{DefaultDelegatedRewardsConfigProvider, DelegatedRewardsConfigProvider}
import io.constellationnetwork.dag.l0.infrastructure.snapshot.GlobalSnapshotContext
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
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeRecord, DelegatedStakeReference, PendingWithdrawal}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.{DelegatedStakeRewardParameters, RewardFraction, UpdateNodeParameters}
import io.constellationnetwork.schema.transaction.{RewardTransaction, TransactionAmount}
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.syntax.sortedCollection.{sortedMapSyntax, sortedSetSyntax}

import eu.timepit.refined.auto.autoUnwrap
import eu.timepit.refined.types.all.PosLong
import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger

object DelegatedRewardsDistributor {

  def make[F[_]: Async: Hasher](
    rewardCfg: ClassicRewardsConfig,
    environment: AppEnvironment,
    configProvider: DelegatedRewardsConfigProvider = DefaultDelegatedRewardsConfigProvider
  ): DelegatedRewardsDistributor[F] = {
    val delegatedRewards = configProvider.getConfig()

    new DelegatedRewardsDistributor[F] {

      /** Calculate the total rewards to mint for this epoch based on appropriate rewards mechanism. The calculation follows this priority:
        *   1. Use the emission formula + pre-configured rewards (if configured) and epoch is past the transition point
        *   1. Use the emission formula and epoch is past the transition point
        *   1. Use preconfigured rewards per epoch mapping for epochs before the emission formula transition (v1 & v2)
        *   1. Return zero rewards if no configuration is available
        */
      def calculateTotalRewardsToMint(epochProgress: EpochProgress): F[Amount] = {

        implicit val rewardOrdering: Ordering[(EpochProgress, Amount)] =
          (x: (EpochProgress, Amount), y: (EpochProgress, Amount)) => x._1.compare(y._1)

        // Get environment-specific configurations
        val envEmissionConfig = delegatedRewards.emissionConfig.get(environment)

        // Get the transition epoch for this environment (if available)
        val transitionEpoch = envEmissionConfig.map(_.asOfEpoch).getOrElse(EpochProgress.MaxValue)

        // First check for static rewards per epoch defined for this environment
        rewardCfg.rewardsPerEpoch.filter {
          case (epoch, _) =>
            epoch.value <= epochProgress.value && epochProgress.value < transitionEpoch.value
        }.maxOption match {
          case Some((_, amount)) if envEmissionConfig.exists(ec => epochProgress.value >= ec.asOfEpoch.value) =>
            calculateEmissionRewards(epochProgress, envEmissionConfig.get)
              .map(_.value + amount.value)
              .map(sum => Amount(NonNegLong.unsafeFrom(sum)))

          case None if envEmissionConfig.exists(ec => epochProgress.value >= ec.asOfEpoch.value) =>
            calculateEmissionRewards(epochProgress, envEmissionConfig.get)

          case Some((_, amount)) =>
            amount.pure[F]

          // Return zero rewards if no environment-specific config exists
          case _ =>
            Slf4jLogger
              .getLogger[F]
              .warn(s"No emission config found for $environment, returning zero rewards")
              .as(Amount(NonNegLong.unsafeFrom(0L)))
        }
      }

      /** Implements the distribute method that encapsulates all reward calculation logic for a consensus cycle. This method replaces the
        * reward calculation functionality that was previously spread across the GlobalSnapshotAcceptanceManager.
        */
      def distribute(
        lastSnapshotContext: GlobalSnapshotInfo,
        trigger: ConsensusTrigger,
        epochProgress: EpochProgress,
        facilitators: NonEmptySet[Id],
        delegatedStakeDiffs: UpdateDelegatedStakeAcceptanceResult,
        partitionedRecords: PartitionedStakeUpdates
      ): F[DelegationRewardsResult] = trigger match {
        case EventTrigger =>
          DelegationRewardsResult(Map.empty, SortedMap.empty, SortedMap.empty, SortedSet.empty, SortedSet.empty, Amount.empty).pure[F]
        case TimeTrigger =>
          applyDistribution(lastSnapshotContext, epochProgress, facilitators, delegatedStakeDiffs, partitionedRecords)
      }

      /** Calculate rewards using the new emission formula with deterministic precision: i(t) = i_initial + (i_initial - i_target) * e^{ -λ
        * * (Y_current - Y_initial) * (P_initial / P_current)^i_impact }
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
          } yield Amount(NonNegLong.unsafeFrom(math.max(0, emissionLong)))
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
        epochProgress: EpochProgress,
        totalRewards: Amount
      ): F[Map[Address, Map[Id, Amount]]] =
        if (activeDelegatedStakes.isEmpty) Map.empty[Address, Map[Id, Amount]].pure[F]
        else
          for {
            programsDistributionConfig <- rewardCfg.programs(epochProgress).pure[F]
            reservedAddressWeights = programsDistributionConfig.weights.values.map(_.toBigDecimal)
            validatorsWeight = programsDistributionConfig.validatorsWeight.toBigDecimal
            delegatorsWeight = programsDistributionConfig.delegatorsWeight.toBigDecimal
            delegatorFlatInflationPercentage = delegatedRewards.flatInflationRate.toBigDecimal

            totalWeight = reservedAddressWeights.sum + validatorsWeight + delegatorsWeight

            // Total rewards specifically allocated for delegators
            // This isn't distributed through reward transactions, but instead accumulated in stake records
            totalDelegationRewards =
              if (totalWeight <= 0) BigDecimal(0)
              else
                BigDecimal(totalRewards.value.value) *
                  (1 + delegatorFlatInflationPercentage) *
                  delegatorsWeight / totalWeight

            // Collect all active delegations grouped by node ID
            activeStakes = activeDelegatedStakes.flatMap {
              case (address, records) =>
                records.map { record =>
                  (record.event.value.nodeId.toId, address, record)
                }
            }

            // Calculate total stake amount
            totalStakeAmount = BigDecimal(activeStakes.map(_._3.event.value.amount.value.value).sum)

            result <-
              if (totalStakeAmount <= 0) Map.empty[Address, Map[Id, Amount]].pure[F]
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
                              totalDelegationRewards *
                                delegatorRewardPercentage *
                                nodePortionOfTotalStake *
                                delegatorPortionOfNodeStake

                            address -> (nodeId -> Amount(NonNegLong.unsafeFrom(math.max(0, delegatorReward.toLong))))
                        }
                      } yield addressRewards
                  }
                  .map(_.groupBy(_._1).view.mapValues(_.map(_._2).toMap).toMap)
              }
          } yield result

      private def calculateNodeOperatorRewards(
        delegatorRewardsMap: Map[Address, Map[Id, Amount]],
        nodeParametersMap: SortedMap[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)],
        nodesInConsensus: NonEmptySet[Id],
        epochProgress: EpochProgress,
        totalRewards: Amount
      ): F[SortedSet[RewardTransaction]] = {
        // Collect total delegator rewards by node ID
        val totalRewardsByNode = delegatorRewardsMap.values.foldLeft(Map.empty[Id, Long]) { (acc, nodeRewards) =>
          nodeRewards.foldLeft(acc) {
            case (nodeAcc, (nodeId, amount)) =>
              val current = nodeAcc.getOrElse(nodeId, 0L)
              nodeAcc.updated(nodeId, current + amount.value.value)
          }
        }

        // Get the configuration for this epoch
        val programsDistributionConfig = rewardCfg.programs(epochProgress)
        val reservedAddressWeights = programsDistributionConfig.weights.values.map(_.toBigDecimal)
        val validatorsWeight = programsDistributionConfig.validatorsWeight.toBigDecimal
        val delegatorsWeight = programsDistributionConfig.delegatorsWeight.toBigDecimal

        // Calculate the total weight for weight-based distribution
        val totalWeight = reservedAddressWeights.sum + validatorsWeight + delegatorsWeight

        // Calculate the total reward pool for validator static rewards
        val staticValidatorRewardPool =
          if (totalWeight <= 0) BigDecimal(0)
          else BigDecimal(totalRewards.value.value) * validatorsWeight / totalWeight

        // Calculate per-validator static rewards based on equal distribution among validators in consensus
        val perValidatorStaticReward =
          if (nodesInConsensus.isEmpty) BigDecimal(0)
          else staticValidatorRewardPool / BigDecimal(nodesInConsensus.size)

        // Calculate dynamic rewards based on network config settings and node parameters
        val operatorRewardsList = totalRewardsByNode.toList.flatMap {
          case (nodeId, totalDelegatorReward) =>
            // Only process nodes that have parameters in the map and are in consensus
            if (!nodesInConsensus.contains(nodeId)) None
            else
              nodeParametersMap.get(nodeId).flatMap {
                case (params, _) =>
                  // Get the operator percentage from node parameters
                  val operatorPercentage = BigDecimal(params.value.delegatedStakeRewardParameters.reward)

                  if (operatorPercentage <= 0 || totalDelegatorReward <= 0) None
                  else {
                    // Calculate dynamic reward component proportional to delegator rewards:
                    val dynamicReward = (BigDecimal(totalDelegatorReward) * operatorPercentage)
                      .setScale(0, RoundingMode.HALF_UP)
                      .toLong

                    // Calculate static reward component (each validator gets equal share)
                    val staticReward = perValidatorStaticReward.setScale(0, RoundingMode.HALF_UP).toLong

                    val transactions = Seq(
                      // Static reward - base validator reward distributed evenly
                      Option.when(staticReward > 0)(
                        RewardTransaction(
                          params.value.source,
                          TransactionAmount(PosLong.unsafeFrom(staticReward))
                        )
                      ),
                      // Dynamic reward - based on node parameters
                      Option.when(dynamicReward > 0)(
                        RewardTransaction(
                          params.value.source,
                          TransactionAmount(PosLong.unsafeFrom(dynamicReward))
                        )
                      )
                    ).flatten

                    if (transactions.nonEmpty) Some(transactions) else None
                  }
              }
        }

        SortedSet.from(operatorRewardsList.flatten).pure[F]
      }

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
        facilitators: NonEmptySet[Id],
        delegatedStakeDiffs: UpdateDelegatedStakeAcceptanceResult,
        partitionedRecords: PartitionedStakeUpdates
      ): F[DelegationRewardsResult] =
        for {
          // Calculate total rewards to mint for this snapshot
          totalEmittedRewardsAmount <- calculateTotalRewardsToMint(epochProgress)

          // Calculate delegator rewards for all active and newly accepted delegated stakes
          delegatorRewardsMap <-
            calculateDelegatorRewards(
              lastSnapshotContext.activeDelegatedStakes.getOrElse(SortedMap.empty),
              lastSnapshotContext.updateNodeParameters.getOrElse(SortedMap.empty),
              epochProgress,
              totalEmittedRewardsAmount
            )

          updatedCreateDelegatedStakes <-
            delegatedStakeDiffs.acceptedCreates.map {
              case (addr, st) =>
                addr -> st.map {
                  case (ev, ord) => DelegatedStakeRecord(ev, ord, Balance.empty, Amount(NonNegLong.unsafeFrom(0L)))
                }
            }.pure[F]
              .map(partitionedRecords.unexpiredCreateDelegatedStakes |+| _)
              .map(_.map {
                case (addr, recs) =>
                  addr -> recs.map {
                    case DelegatedStakeRecord(event, ord, bal, _) =>
                      val nodeSpecificReward = delegatorRewardsMap
                        .get(addr)
                        .flatMap(_.get(event.value.nodeId.toId))
                        .getOrElse(Amount.empty)

                      val disbursedBalance = bal.plus(nodeSpecificReward).toOption.getOrElse(Balance.empty)

                      DelegatedStakeRecord(event, ord, disbursedBalance, nodeSpecificReward)
                  }
              })

          updatedWithdrawDelegatedStakes <-
            delegatedStakeDiffs.acceptedWithdrawals.toList.traverse {
              case (addr, acceptedWithdrawls) =>
                acceptedWithdrawls.traverse {
                  case (ev, ep) =>
                    lastSnapshotContext.activeDelegatedStakes
                      .flatTraverse(_.get(addr).flatTraverse {
                        _.findM { s =>
                          DelegatedStakeReference.of(s.event).map(_.hash === ev.stakeRef)
                        }.map(_.map(rec => PendingWithdrawal(ev, rec.rewards, ep)))
                      })
                      .flatMap(Async[F].fromOption(_, new RuntimeException("Unexpected None when processing user delegations")))
                }.map(addr -> _)
            }.map(_.toSortedMap).map(partitionedRecords.unexpiredWithdrawalsDelegatedStaking |+| _)

          nodeOperatorRewards <-
            calculateNodeOperatorRewards(
              delegatorRewardsMap,
              lastSnapshotContext.updateNodeParameters.getOrElse(SortedMap.empty),
              facilitators,
              epochProgress,
              totalEmittedRewardsAmount
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

        } yield
          DelegationRewardsResult(
            delegatorRewardsMap,
            updatedCreateDelegatedStakes,
            updatedWithdrawDelegatedStakes,
            nodeOperatorRewards,
            withdrawalRewardTxs,
            totalEmittedRewardsAmount
          )
    }
  }
}
