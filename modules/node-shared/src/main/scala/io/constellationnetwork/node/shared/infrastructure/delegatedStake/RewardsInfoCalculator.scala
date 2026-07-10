package io.constellationnetwork.node.shared.infrastructure.delegatedStake

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.math.BigDecimal.RoundingMode

import io.constellationnetwork.node.shared.infrastructure.snapshot.DelegatedRewardsDistributor
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.PricingUpdate
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.priceOracle.{PriceRecord, TokenPair}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.utils.DecimalUtils

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosLong

trait RewardsInfoCalculator[F[_]] {
  def calculateRewardsInfo(
    lastSnapshot: GlobalIncrementalSnapshot,
    lastSnapshotInfo: GlobalSnapshotInfo
  ): F[Option[RewardsInfo]]
}

object RewardsInfoCalculator {
  def make[F[_]: Async](delegatedRewardsDistributor: DelegatedRewardsDistributor[F]): RewardsInfoCalculator[F] = {
    new RewardsInfoCalculator[F] {
      override def calculateRewardsInfo(
        lastSnapshot: GlobalIncrementalSnapshot,
        lastSnapshotInfo: GlobalSnapshotInfo
      ): F[Option[RewardsInfo]] =
        if (lastSnapshot.delegateRewards.getOrElse(SortedMap.empty[PeerId, SortedMap[Address, Amount]]).isEmpty) {
          Option.empty[RewardsInfo].pure[F]
        } else {
          for {
            latestDelegateRewardsNoCommission <- getLatestDelegateRewardTotal(lastSnapshot, lastSnapshotInfo)

            (_, _, totalDelegateStake, currentTotalSupply) <- processDelegations(lastSnapshotInfo)

            currentPrice <- toAmount(getCurrentDagPrice(lastSnapshotInfo))
            nextPrice <- getNextDagPrice(lastSnapshotInfo)

            avgRewardAmount <- calculateAverageReward(latestDelegateRewardsNoCommission, totalDelegateStake)
            emissionConfig <- delegatedRewardsDistributor.getEmissionConfig(lastSnapshot.epochProgress)
            totalRewardsPerYear <- calculateAverageRewardOverAYear(avgRewardAmount, emissionConfig.epochsPerYear)
          } yield
            Some(
              RewardsInfo(
                epochsPerYear = emissionConfig.epochsPerYear,
                currentDagPrice = currentPrice,
                nextDagPrice = nextPrice,
                totalDelegatedAmount = totalDelegateStake,
                latestAverageRewardPerDag = avgRewardAmount,
                totalDagAmount = currentTotalSupply,
                totalRewardPerEpoch = latestDelegateRewardsNoCommission,
                totalRewardsPerYearEstimate = totalRewardsPerYear
              )
            )
        }

      private def processDelegations(info: GlobalSnapshotInfo): F[(Amount, Amount, Amount, Amount)] = {
        val activeDelegatedStakes = info.activeDelegatedStakes
          .getOrElse(SortedMap.empty[Address, List[DelegatedStakeRecord]])

        val pendingWithdrawals = info.delegatedStakesWithdrawals
          .getOrElse(SortedMap.empty[Address, List[PendingDelegatedStakeWithdrawal]])

        val totalSpendableSupply = info.balances.values.map(_.value.value).sum
        val totalPendingSupply = pendingWithdrawals.values.flatten.map(_.rewards.value.value).sum

        val (totalStakeLocked, totalActiveRewards) = activeDelegatedStakes.values.flatten.foldLeft((0L, 0L)) {
          case ((stakeAcc, rewardsAcc), record) =>
            val stakeAmount = record.event.value.amount.value.value
            val rewardsAmount = record.rewards.value
            (stakeAcc + stakeAmount, rewardsAcc + rewardsAmount)
        }

        (
          toAmount(totalStakeLocked),
          toAmount(totalActiveRewards),
          toAmount(totalStakeLocked + totalActiveRewards), // totalDelegateStake
          toAmount(totalSpendableSupply + totalPendingSupply + totalActiveRewards) // currentTotalSupply
        ).mapN(Tuple4.apply)
      }

      private def getLatestDelegateRewardTotal(snapshot: GlobalIncrementalSnapshot, info: GlobalSnapshotInfo): F[Amount] = {
        val delegateRewards = snapshot.delegateRewards.getOrElse(SortedMap.empty[PeerId, SortedMap[Address, Amount]])
        val nodeParams = info.updateNodeParameters
          .getOrElse(SortedMap.empty[ID.Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)])

        val calcFullReward: (Long, (PeerId, SortedMap[Address, Amount])) => Long = {
          case (acc, (peerId, rewards)) =>
            val nodeCommissionValue = nodeParams.get(peerId.toId).map(_._1.delegatedStakeRewardParameters.reward).getOrElse(0.0)
            val nodeCommission = BigDecimal(nodeCommissionValue)

            val delegatePortion = if (nodeCommission >= 1.0) BigDecimal(0.0) else BigDecimal(1.0) - nodeCommission

            val rewardsSum = rewards.values.map(_.value.value).sum
            val rewardsBigDecimal = BigDecimal(rewardsSum)

            if (delegatePortion == BigDecimal(0.0)) acc
            else
              acc + (rewardsBigDecimal / delegatePortion)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue
        }

        delegateRewards
          .foldLeft(0L)(calcFullReward)
          .pure[F]
          .flatMap(toAmount)
      }

      private def calculateAverageReward(latestRewards: Amount, totalStakedAmount: Amount): F[BigDecimal] =
        if (totalStakedAmount.value.value === 0) BigDecimal(0).pure[F]
        else
          (BigDecimal(latestRewards.value.value) / BigDecimal(totalStakedAmount.value.value)).pure[F]

      private def calculateAverageRewardOverAYear(avgReward: BigDecimal, epochsPerYear: PosLong): F[BigDecimal] =
        (avgReward * BigDecimal(epochsPerYear.value)).pure[F]

      private def toAmount(value: Long): F[Amount] =
        if (value == 0L) Amount.empty.pure[F]
        else
          PosLong
            .from(value)
            .pure[F]
            .map(_.leftMap(new IllegalArgumentException(_)))
            .flatMap(Async[F].fromEither(_))
            .map(Amount(_))

      private def priceToLong(pricingUpdate: PricingUpdate): Long =
        (pricingUpdate.price.value.toBigDecimal * DecimalUtils.DATUM_USD).setScale(0, RoundingMode.HALF_UP).longValue

      private def getCurrentDagPrice(info: GlobalSnapshotInfo): Long =
        info.priceState
          .getOrElse(SortedMap.empty[TokenPair, PriceRecord])
          .get(TokenPair.DAG_USD)
          .map(priceRecord => priceToLong(priceRecord.currentPrice))
          .getOrElse(0L)

      private def getNextDagPrice(info: GlobalSnapshotInfo): F[NextDagPrice] = {
        val maybePriceRecord = info.priceState
          .getOrElse(SortedMap.empty[TokenPair, PriceRecord])
          .get(TokenPair.DAG_USD)

        maybePriceRecord match {
          case Some(priceRecord) =>
            val priceValue = priceToLong(priceRecord.upcomingPrice)
            PosLong
              .from(priceValue)
              .fold(
                err => Async[F].raiseError(new IllegalArgumentException(s"Failed to create positive price: $err")),
                posLong =>
                  NextDagPrice(
                    price = Amount(posLong),
                    asOfEpoch = priceRecord.nextWindowChange
                  ).pure[F]
              )
          case None =>
            PosLong
              .from(1L)
              .fold(
                err => Async[F].raiseError(new IllegalArgumentException(s"Failed to create positive epoch: $err")),
                posLong =>
                  NextDagPrice(
                    price = Amount.empty,
                    asOfEpoch = EpochProgress(posLong)
                  ).pure[F]
              )
        }
      }
    }
  }
}
