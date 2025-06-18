package io.constellationnetwork.dag.l0.infrastructure.rewards

import java.math.MathContext

import cats.Applicative
import cats.effect.{Async, Ref, Sync}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.math.BigDecimal.RoundingMode

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceResult
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.schema.AmountOps._
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance._
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
import io.constellationnetwork.utils.DecimalUtils

import eu.timepit.refined.auto.autoUnwrap
import eu.timepit.refined.types.all.{NonNegLong, PosLong}
import org.typelevel.log4cats.slf4j.Slf4jLogger

object GlobalDelegatedRewardsDistributor {

  def make[F[_]: Async: Hasher](
    environment: AppEnvironment,
    delegatedRewardsConfig: DelegatedRewardsConfig
  ): F[DelegatedRewardsDistributor[F]] = {
    implicit val reverseOrdering: Ordering[(EpochProgress, SnapshotOrdinal)] =
      new Ordering[(EpochProgress, SnapshotOrdinal)] {
        def compare(x: (EpochProgress, SnapshotOrdinal), y: (EpochProgress, SnapshotOrdinal)): Int = {
          val epochCmp = x._1.compare(y._1)
          val ordinalCmp = x._2.compare(y._2)
          val result = if (epochCmp != 0) epochCmp else ordinalCmp
          -result // negate the result to reverse the ordering
        }
      }

    Ref.of[F, SortedSet[(EpochProgress, SnapshotOrdinal)]](SortedSet.empty).map { latestRewardsRef =>
      new DelegatedRewardsDistributor[F] {

        // Define a high precision MathContext for consistent calculations
        private val mc: MathContext = new java.math.MathContext(24, java.math.RoundingMode.HALF_UP)

    /** Return emission configuration applied by this rewards distributor
      */
    def getEmissionConfig(epochProgress: EpochProgress): F[EmissionConfigEntry] =
      delegatedRewardsConfig.emissionConfig
        .get(environment)
        .pure[F]
        .flatMap(Async[F].fromOption(_, new RuntimeException(s"Could not retrieve emission config for env: $environment")))
        .map(f => f(epochProgress))

        def getDistributionProgram(epochProgress: EpochProgress): F[ProgramsDistributionConfig] =
          delegatedRewardsConfig.percentDistribution
            .get(environment)
            .pure[F]
            .flatMap(
              Async[F]
                .fromOption(_, new RuntimeException(s"Could not retrieve program distribution config for env: $environment"))
            )
            .map(f => f(epochProgress))

    /** Calculate the variable amount of rewards to mint for this epoch based on the config and epoch progress.
      */
    def calculateVariableInflation(epochProgress: EpochProgress): F[Amount] = for {
      emConfig <- getEmissionConfig(epochProgress)
      result <- calculateEmissionRewards(epochProgress, emConfig)
    } yield result

        /** Implements the distribute method that encapsulates all reward calculation logic for a consensus cycle. This method replaces the
          * reward calculation functionality that was previously spread across the GlobalSnapshotAcceptanceManager.
          */
        def distribute(
          lastSnapshotContext: GlobalSnapshotInfo,
          trigger: ConsensusTrigger,
          ordinal: SnapshotOrdinal,
          epochProgress: EpochProgress,
          facilitators: List[(Address, PeerId)],
          delegatedStakeDiffs: UpdateDelegatedStakeAcceptanceResult,
          partitionedRecords: PartitionedStakeUpdates
        ): F[DelegatedRewardsResult] =
          trigger match {
            case EventTrigger =>
              applyDistribution(
                lastSnapshotContext,
                ordinal,
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
                pctConfig <- getDistributionProgram(epochProgress)
                epochSPerYear <- getEmissionConfig(epochProgress).map(_.epochsPerYear.value)
                (reservedRewards, facilitatorRewardPool, delegatorRewardPool) <- calculateEmissionDistribution(
                  lastSnapshotContext.activeDelegatedStakes.getOrElse(SortedMap.empty),
                  emitFromFunction,
                  pctConfig,
                  epochSPerYear
                )
                result <- applyDistribution(
                  lastSnapshotContext,
                  ordinal,
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
          activeDelegatedStakes: SortedMap[Address, SortedSet[DelegatedStakeRecord]],
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
            val yearDiff = DecimalUtils.safeDivide(BigDecimal(epochProgress.value.value - transitionEpoch.toLong, mc), epochsPerYear)

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
              perEpochEmissionValue = DecimalUtils.safeDivide(annualEmissionValue, epochsPerYear)
              amount <- perEpochEmissionValue.roundedHalfUp(0).toAmount[F]
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
            .getOrElse(dagPrices.head._2)

        private def getStakedAmount(stakeRecord: DelegatedStakeRecord): Long =
          stakeRecord.event.value.amount.value.value + stakeRecord.rewards.value

        private def getTotalActiveStake(
          activeDelegatedStakes: SortedMap[Address, SortedSet[DelegatedStakeRecord]]
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
          activeDelegatedStakes: SortedMap[Address, SortedSet[DelegatedStakeRecord]],
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

                            rewardBD.roundedHalfUp(0).toAmount[F].map { rewardAmount =>
                              nodeId.toPeerId -> (address -> rewardAmount)
                            }
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

          val staticRewardsF = {
            val facilitatorCount = BigDecimal(facilitators.size, mc)
            val perValidatorStaticReward =
              if (facilitators.isEmpty) BigDecimal(0, mc)
              else facilitatorRewardPool / facilitatorCount

            facilitators
              .traverse[F, Option[RewardTransaction]] {
                case (addr, _) =>
                  val staticRewardBD = perValidatorStaticReward.roundedHalfUp(0)
                  if (staticRewardBD <= 0) Option.empty[RewardTransaction].pure[F]
                  else
                    staticRewardBD
                      .toTransactionAmount[F]
                      .map(RewardTransaction(addr, _).some)
              }
              .map(_.flatten)
          }

          val activeDelegatedStakes =
            lastSnapshotContext.activeDelegatedStakes.getOrElse(SortedMap.empty[Address, SortedSet[DelegatedStakeRecord]])

          if (activeDelegatedStakes.isEmpty || (delegatorRewardPool === BigDecimal(0) && facilitatorRewardPool === BigDecimal(0))) {
            staticRewardsF.map(staticRewards => SortedSet.from(staticRewards))
          } else {
            for {
              staticRewards <- staticRewardsF
              modifiedStakes = DelegatedRewardsDistributor.identifyModifiedStakes(activeDelegatedStakes, acceptedCreates)
              filteredActiveDelegatedStakes = DelegatedRewardsDistributor.filterOutModifiedStakes(activeDelegatedStakes, modifiedStakes)
              nodeStakes = filteredActiveDelegatedStakes.values.flatten
                .groupBy(_.event.value.nodeId.toId)
                .view
                .mapValues(stakes => stakes.map(s => BigDecimal(getStakedAmount(s), mc)).sum)
                .toMap

              facilitatorStakes = facilitators.map {
                case (_, id) =>
                  (id, nodeStakes.getOrElse(id.toId, BigDecimal(0, mc)))
              }

              totalFacilitatorStake = facilitatorStakes.map(_._2).sum

              dynamicRewards <- facilitatorStakes.flatMap {
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
                        val roundedDynamicReward = dynamicRewardBD.roundedHalfUp(0)
                        if (roundedDynamicReward <= 0) None
                        else
                          roundedDynamicReward
                            .toTransactionAmount[F]
                            .map(RewardTransaction(params.value.source, _))
                            .some
                    }
                  }
              }.sequence

              allRewards = SortedSet.from(staticRewards ++ dynamicRewards)
            } yield allRewards
          }
        }

        private def calculateReservedRewards(
          reservedRewards: List[(Address, BigDecimal)]
        ): F[SortedSet[RewardTransaction]] =
          reservedRewards.traverse {
            case (addr, amt) =>
              amt.roundedHalfUp(0).toTransactionAmount[F].map { amount =>
                RewardTransaction(
                  addr,
                  TransactionAmount(amount.value)
                )
              }
          }.map(SortedSet.from(_))

        private def calculateWithdrawalRewardTransactions(
          withdrawingBalances: Map[Address, Amount]
        ): F[SortedSet[RewardTransaction]] =
          withdrawingBalances.toList.filter { case (_, amount) => amount.value.value > 0 }.traverse {
            case (address, amount) =>
              BigDecimal(amount.value.value)
                .roundedHalfUp(0)
                .toTransactionAmount[F]
                .map(RewardTransaction(address, _))
          }.map(SortedSet.from(_))

        private def applyDistribution(
          lastSnapshotContext: GlobalSnapshotInfo,
          ordinal: SnapshotOrdinal,
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
                    withdrawals.toList.mapFilter { withdrawal =>
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

            _ <- latestRewardsRef.update { current =>
              val maxSize = delegatedRewardsConfig.maxKnownRewardTicks
              val updated = current + ((epochProgress, ordinal))

              if (updated.size > maxSize) updated.drop(updated.size - maxSize)
              else updated
            }

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

        def getKnownRewardsTicks: F[SortedSet[(EpochProgress, SnapshotOrdinal)]] = latestRewardsRef.get
      }
    }
  }
}
