package io.constellationnetwork.dag.l0.infrastructure.rewards

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import io.constellationnetwork.node.shared.config.types.{OneTimeReward, ProgramsDistributionConfig}
import io.constellationnetwork.node.shared.config.{DefaultDelegatedRewardsConfigProvider, DelegatedRewardsEra, MainnetRewardsConfig}
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceResult
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeRecord, DelegatedStakeReference}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.transaction.{RewardTransaction, TransactionAmount}
import io.constellationnetwork.schema.{GlobalSnapshotInfo, NonNegFraction, SnapshotOrdinal}
import io.constellationnetwork.security.Hasher

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}

private[rewards] object NewEraDelegatedRewards {
  sealed abstract class DistributionError(message: String) extends RuntimeException(message) with NoStackTrace
  final case class InvalidAmount(value: BigInt) extends DistributionError(s"Reward amount outside the supported range: $value")
  case object DuplicateStake extends DistributionError("Multiple active stakes share a delegator and node")
  case object AmbiguousFacilitator extends DistributionError("A reward facilitator has multiple addresses")
  case object InconsistentAccrual extends DistributionError("Credited delegation rewards do not match the allocated reward budget")
  final case class InvalidProgram(reason: String) extends DistributionError(reason)

  private def programAllocation(
    budget: Amount,
    program: ProgramsDistributionConfig
  ): Either[DistributionError, (Map[Int, BigInt], List[(Address, Int)], NonNegFraction)] = {
    val canonicalReserved = List(
      MainnetRewardsConfig.protocolWalletMetanomics -> 0,
      DefaultDelegatedRewardsConfigProvider.stardustTreasury -> 1,
      DefaultDelegatedRewardsConfigProvider.aiInference -> 4
    )
    val additional = program.weights.keys.toList.filterNot(canonicalReserved.map(_._1).contains).sorted.zipWithIndex.map {
      case (address, index) => address -> (index + 5)
    }
    val reserved = (canonicalReserved ++ additional).filter { case (address, _) => program.weights.contains(address) }
    val fractions = reserved.map { case (address, key) => key -> program.weights(address) } ++
      List(2 -> program.validatorsWeight, 3 -> program.delegatorsWeight)
    val denominator = fractions.map { case (_, value) => BigInt(value.denominator.value) }.foldLeft(BigInt(1))((left, right) =>
      left / left.gcd(right) * right
    )
    val weights = fractions.map { case (key, value) => key -> (BigInt(value.numerator.value) * (denominator / value.denominator.value)) }
    for {
      _ <- Either.cond(weights.map(_._2).sum == denominator, (), InvalidProgram("Reward program weights must sum to one"))
      _ <- Either.cond(
        program.flatInflationRate.exists(_.numerator.value == 0L),
        (),
        InvalidProgram("New-era delegation top-up must be explicitly zero")
      )
      commission <- program.fixedCommission.toRight(InvalidProgram("New-era commission must be explicitly configured"))
      _ <- Either.cond(
        BigInt(commission.numerator.value) * DefaultDelegatedRewardsConfigProvider.fixedCommission.denominator.value ==
          BigInt(DefaultDelegatedRewardsConfigProvider.fixedCommission.numerator.value) * commission.denominator.value,
        (),
        InvalidProgram("New-era commission must equal the fixed ten percent policy")
      )
    } yield (RewardApportionment.allocate(BigInt(budget.value.value), weights).toMap, reserved, commission)
  }

  private def amount(value: BigInt): Either[DistributionError, Amount] =
    Either
      .cond(value >= 0 && value.isValidLong, value.toLong, InvalidAmount(value))
      .flatMap(v => NonNegLong.from(v).leftMap(_ => InvalidAmount(value)).map(Amount(_)))

  private def transactions[F[_]: Async](values: List[(Address, BigInt)]): F[SortedSet[RewardTransaction]] =
    values
      .groupMapReduce(_._1)(_._2)(_ + _)
      .toList
      .filter(_._2 > 0)
      .traverse {
        case (address, value) =>
          amount(value).liftTo[F].flatMap { checked =>
            PosLong
              .from(checked.value.value)
              .leftMap(_ => InvalidAmount(value))
              .liftTo[F]
              .map(v => RewardTransaction(address, TransactionAmount(v)))
          }
      }
      .map(SortedSet.from(_))

  private def stakeAmount(record: DelegatedStakeRecord): BigInt =
    BigInt(record.event.value.amount.value.value) + BigInt(record.rewards.value.value)

  def distribute[F[_]: Async: Hasher](
    context: GlobalSnapshotInfo,
    ordinal: SnapshotOrdinal,
    era: DelegatedRewardsEra,
    budget: Amount,
    facilitators: List[(Address, PeerId)],
    diffs: UpdateDelegatedStakeAcceptanceResult,
    partitioned: PartitionedStakeUpdates,
    oneTimeRewards: List[OneTimeReward],
    program: ProgramsDistributionConfig
  ): F[DelegatedRewardsResult] = {
    val active = partitioned.unexpiredCreateDelegatedStakes
    val modified = DelegatedRewardsDistributor.identifyModifiedStakes(active, diffs.acceptedCreates)
    val unmodified = DelegatedRewardsDistributor.filterOutModifiedStakes(active, modified)
    val withdrawn = diffs.acceptedWithdrawals.values.flatten.map(_._1.stakeRef).toSet
    val eligibleFacilitators = facilitators.distinct.filter { case (_, id) => era.validatorsAt(ordinal).contains(id) }
    val parameters = context.updateNodeParameters.toList.flatMap(_.toList).toMap
    for {
      allocation <- programAllocation(budget, program).liftTo[F]
      (pools, reserved, commissionFraction) = allocation
      grossDelegation = pools.getOrElse(3, BigInt(0))
      commission = grossDelegation * commissionFraction.numerator.value / commissionFraction.denominator.value
      _ <- Async[F]
        .raiseError[Unit](AmbiguousFacilitator)
        .whenA(eligibleFacilitators.groupBy(_._2).exists(_._2.size > 1))
      stakes <- unmodified.toList.flatTraverse {
        case (address, records) =>
          records.toList.traverseFilter { record =>
            DelegatedStakeReference.of[F](record.event).map { reference =>
              Option.unless(withdrawn.contains(reference.hash))((record.event.value.nodeId, address, record))
            }
          }
      }
      _ <- Async[F]
        .raiseError[Unit](DuplicateStake)
        .whenA(stakes.groupBy { case (node, address, _) => (node, address) }.exists(_._2.size > 1))
      delegationAllocation = RewardApportionment.allocate(
        grossDelegation - commission,
        stakes.map { case (node, address, record) => (node, address) -> stakeAmount(record) }
      )
      delegationAmounts <- delegationAllocation.filter(_._2 > 0).traverse {
        case ((node, address), value) => amount(value).liftTo[F].map(v => node -> (address -> v))
      }
      delegationRewards = SortedMap.from(delegationAmounts.groupMap(_._1)(_._2).map { case (node, rewards) => node -> rewards.toMap })
      nodeStakes = stakes.groupMapReduce(_._1) { case (_, _, record) => stakeAmount(record) }(_ + _)
      registeredFacilitators = eligibleFacilitators.filter { case (_, id) => parameters.contains(id.toId) }
      commissionAllocation = RewardApportionment.allocate(
        if (stakes.nonEmpty) commission else BigInt(0),
        registeredFacilitators.map { case (_, id) => id -> nodeStakes.getOrElse(id, BigInt(0)) }
      )
      directAllocation = RewardApportionment.allocate(
        pools.getOrElse(2, BigInt(0)),
        eligibleFacilitators.map { case (_, id) => id -> BigInt(1) }
      )
      facilitatorAddresses = eligibleFacilitators.map { case (address, id) => id -> address }.toMap
      operatorRewards <- transactions[F](
        directAllocation.map { case (id, value) => facilitatorAddresses(id) -> value } ++
          commissionAllocation.flatMap {
            case (id, value) => parameters.get(id.toId).map { case (params, _) => params.value.source -> value }
          } ++
          oneTimeRewards.map(r => r.address -> BigInt(r.amount.value.value))
      )
      reservedRewards <- transactions[F](reserved.map { case (address, key) => address -> pools.getOrElse(key, BigInt(0)) })
      updatedCreates <- DelegatedRewardsDistributor.getUpdatedCreateDelegatedStakes[F](delegationRewards, diffs, partitioned)
      baselineCreates <- DelegatedRewardsDistributor.getUpdatedCreateDelegatedStakes[F](Map.empty, diffs, partitioned)
      creditedRewards = updatedCreates.values.flatten.map(r => BigInt(r.rewards.value.value)).sum -
        baselineCreates.values.flatten.map(r => BigInt(r.rewards.value.value)).sum
      allocatedRewards = delegationAmounts.map { case (_, (_, value)) => BigInt(value.value.value) }.sum
      _ <- Async[F].raiseError[Unit](InconsistentAccrual).whenA(creditedRewards != allocatedRewards)
      updatedWithdrawals <- DelegatedRewardsDistributor.getUpdatedWithdrawalDelegatedStakes[F](context, diffs, partitioned)
      withdrawalRewards <- transactions[F](partitioned.expiredWithdrawalsDelegatedStaking.toList.flatMap {
        case (address, withdrawals) => withdrawals.toList.map(w => address -> BigInt(w.rewards.value.value))
      })
      totalMinted <- amount(
        operatorRewards.toList.map(r => BigInt(r.amount.value.value)).sum +
          reservedRewards.toList.map(r => BigInt(r.amount.value.value)).sum + allocatedRewards
      ).liftTo[F]
    } yield
      DelegatedRewardsResult(
        delegationRewards,
        updatedCreates,
        updatedWithdrawals,
        operatorRewards,
        reservedRewards,
        withdrawalRewards,
        totalMinted
      )
  }
}
