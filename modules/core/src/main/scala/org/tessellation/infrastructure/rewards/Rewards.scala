package org.tessellation.infrastructure.rewards

import cats.arrow.FunctionK.liftFunction
import cats.data._
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.config.types.RewardsConfig
import org.tessellation.ext.refined._
import org.tessellation.infrastructure.snapshot.GlobalSnapshotEvent
import org.tessellation.schema.ID.Id
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.transaction.{RewardTransaction, Transaction, TransactionAmount}
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotStateProof, SnapshotOrdinal}
import org.tessellation.sdk.domain.rewards.Rewards
import org.tessellation.sdk.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import org.tessellation.security.signature.Signed
import org.tessellation.syntax.sortedCollection._

import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import io.estatico.newtype.ops._

object Rewards {
  def make[F[_]: Async](
    config: RewardsConfig,
    programsDistributor: ProgramsDistributor[Either[ArithmeticException, *]],
    facilitatorDistributor: FacilitatorDistributor[F]
  ): Rewards[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotEvent] =
    new Rewards[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotEvent] {

      private def getAmountByEpoch(epochProgress: EpochProgress, rewardsPerEpoch: SortedMap[EpochProgress, Amount]): Amount =
        rewardsPerEpoch
          .minAfter(epochProgress)
          .map { case (_, reward) => reward }
          .getOrElse(Amount.empty)

      def distribute(
        lastArtifact: Signed[GlobalIncrementalSnapshot],
        lastBalances: SortedMap[Address, Balance],
        acceptedTransactions: SortedSet[Signed[Transaction]],
        trigger: ConsensusTrigger,
        events: Set[GlobalSnapshotEvent]
      ): F[SortedSet[RewardTransaction]] = {
        val facilitators = lastArtifact.proofs.map(_.id)

        feeDistribution(lastArtifact.ordinal, acceptedTransactions, facilitators).flatMap { feeRewardTxs =>
          trigger match {
            case EventTrigger => feeRewardTxs.pure[F]
            case TimeTrigger  => mintedDistribution(lastArtifact.epochProgress, facilitators).map(_ ++ feeRewardTxs)
          }
        }
      }

      def feeDistribution(
        snapshotOrdinal: SnapshotOrdinal,
        transactions: SortedSet[Signed[Transaction]],
        facilitators: NonEmptySet[Id]
      ): F[SortedSet[RewardTransaction]] = {

        val totalFee = transactions.toList
          .map(_.fee.coerce)
          .sumAll
          .map(Amount(_))
          .liftTo[F]

        Random.scalaUtilRandomSeedLong(snapshotOrdinal.value).flatMap { randomizer =>
          totalFee.flatMap { amount =>
            facilitatorDistributor
              .distribute(randomizer, facilitators)
              .run(amount)
              .flatTap(validateState(amount))
          }.map(toTransactions)
        }
      }

      def mintedDistribution(
        epochProgress: EpochProgress,
        facilitators: NonEmptySet[Id]
      ): F[SortedSet[RewardTransaction]] =
        Random.scalaUtilRandomSeedLong(epochProgress.coerce).flatMap { random =>
          val allRewardsState = for {
            programRewards <- programsDistributor.distribute(config.programs(epochProgress)).mapK[F](liftFunction(_.liftTo[F]))
            facilitatorRewards <- facilitatorDistributor.distribute(random, facilitators)
          } yield programRewards ++ facilitatorRewards

          val mintedAmount = getAmountByEpoch(epochProgress, config.rewardsPerEpoch)

          val rewards: F[SortedSet[RewardTransaction]] = allRewardsState
            .run(mintedAmount)
            .flatTap(validateState(mintedAmount))
            .map(toTransactions)

          val oneTimeRewards = config.oneTimeRewards
            .filter(_.epoch === epochProgress)
            .map(otr => RewardTransaction(otr.address, otr.amount))

          rewards.map(_ ++ oneTimeRewards)
        }

      private def validateState(totalPool: Amount)(state: (Amount, List[(Address, Amount)])): F[Unit] = {
        val (remaining, _) = state

        new RuntimeException(s"Remainder exists in distribution {totalPool=${totalPool.show}, remainingAmount=${remaining.show}}")
          .raiseError[F, Unit]
          .whenA(remaining =!= Amount.empty)
      }

      private def toTransactions(state: (Amount, List[(Address, Amount)])): SortedSet[RewardTransaction] = {
        val (_, rewards) = state

        rewards.flatMap {
          case (address, amount) =>
            refineV[Positive](amount.coerce.value).toList.map(a => RewardTransaction(address, TransactionAmount(a)))
        }.toSortedSet
      }
    }
}
