package io.constellationnetwork.dag.l0.infrastructure.rewards

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import io.constellationnetwork.node.shared.config.DelegatedRewardsEra
import io.constellationnetwork.node.shared.config.types.OneTimeReward
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceResult
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeRecord, DelegatedStakeReference}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.transaction.{RewardTransaction, TransactionAmount}
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.Hasher

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}

private[rewards] object NewEraDelegatedRewards {
  sealed abstract class DistributionError(message: String) extends RuntimeException(message) with NoStackTrace
  final case class InvalidAmount(value: BigInt) extends DistributionError(s"Reward amount outside the supported range: $value")
  case object DuplicateStake extends DistributionError("Multiple active stakes share a delegator and node")
  case object AmbiguousFacilitator extends DistributionError("A reward facilitator has multiple addresses")
  case object InconsistentAccrual extends DistributionError("Credited delegation rewards do not match the allocated reward budget")

  private val protocol = Address("DAG86Joz5S7hkL8N9yqTuVs5vo1bzQLwF3MUTUMX")
  private val treasury = Address("DAG7BfLURyapaaLoJJtR1LndV1s7NnagS8eqZnQ8")
  private val inference = Address("DAG6CLeP4qepXq36xtzYEdKdn5NB1eDWF6ViJmH8")

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
    oneTimeRewards: List[OneTimeReward]
  ): F[DelegatedRewardsResult] = {
    val active = partitioned.unexpiredCreateDelegatedStakes
    val modified = DelegatedRewardsDistributor.identifyModifiedStakes(active, diffs.acceptedCreates)
    val unmodified = DelegatedRewardsDistributor.filterOutModifiedStakes(active, modified)
    val withdrawn = diffs.acceptedWithdrawals.values.flatten.map(_._1.stakeRef).toSet
    val eligibleFacilitators = facilitators.distinct.filter { case (_, id) => era.validatorsAt(ordinal).contains(id) }
    val parameters = context.updateNodeParameters.toList.flatMap(_.toList).toMap
    val pools = RewardApportionment
      .allocate(BigInt(budget.value.value), List(0 -> BigInt(30), 1 -> BigInt(10), 2 -> BigInt(10), 3 -> BigInt(35), 4 -> BigInt(15)))
      .toMap
    val grossDelegation = pools.getOrElse(3, BigInt(0))
    val commission = grossDelegation / 10

    for {
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
      reservedRewards <- transactions[F](
        List(
          protocol -> pools.getOrElse(0, BigInt(0)),
          treasury -> pools.getOrElse(1, BigInt(0)),
          inference -> pools.getOrElse(4, BigInt(0))
        )
      )
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
