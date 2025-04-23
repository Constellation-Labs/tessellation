package io.constellationnetwork.dag.l0.http.routes

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.math.BigDecimal.RoundingMode

import io.constellationnetwork.dag.l0.domain.delegatedStake.{CreateDelegatedStakeOutput, DelegatedStakeOutput, WithdrawDelegatedStakeOutput}
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.ext.http4s.AddressVar
import io.constellationnetwork.kernel._
import io.constellationnetwork.node.shared.config.types.{DelegatedRewardsConfig, EmissionConfigEntry}
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeValidator
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.shapes._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger
import shapeless._
import shapeless.syntax.singleton._

final case class DelegatedStakesRoutes[F[_]: Async: Hasher](
  mkCell: DelegatedStakeOutput => Cell[F, StackF, _, Either[CellError, Ω], _],
  validator: UpdateDelegatedStakeValidator[F],
  snapshotStorage: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  nodeStorage: NodeStorage[F],
  withdrawalTimeLimit: EpochProgress,
  environment: AppEnvironment,
  delegatedRewardsConfig: DelegatedRewardsConfig
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

  private val logger = Slf4jLogger.getLoggerFromName[F]("DelegatedStakesLogger")

  protected val prefixPath: InternalUrlPrefix = "/delegated-stakes"

  private def getDelegatedStakesInfo(address: Address, info: GlobalSnapshotInfo): F[DelegatedStakesInfo] = {
    val lastStakes: List[DelegatedStakeRecord] =
      info.activeDelegatedStakes
        .getOrElse(SortedMap.empty[Address, List[DelegatedStakeRecord]])
        .getOrElse(address, List.empty)
    val lastWithdrawals: List[PendingDelegatedStakeWithdrawal] =
      info.delegatedStakesWithdrawals
        .getOrElse(SortedMap.empty[Address, List[PendingDelegatedStakeWithdrawal]])
        .getOrElse(address, List.empty)

    for {
      stakes <- lastStakes.traverse {
        case DelegatedStakeRecord(stake, ord, bal) =>
          DelegatedStakeReference
            .of(stake)
            .map(ref => (stake, ord, bal, ref))
      }
      active <- stakes.traverse {
        case (stake, acceptedOrdinal, rewardsBalance, delegatedStakeRef) =>
          val totalAmountF = Async[F].fromEither(
            NonNegLong
              .from(rewardsBalance.value + stake.amount.value)
              .leftMap(err => new IllegalArgumentException(s"Failed to create non-negative total: $err"))
              .map(Amount(_))
          )

          totalAmountF.map { total =>
            DelegatedStakeInfo(
              nodeId = stake.nodeId,
              acceptedOrdinal = acceptedOrdinal,
              tokenLockRef = stake.tokenLockRef,
              amount = stake.amount,
              fee = stake.fee,
              hash = delegatedStakeRef.hash,
              withdrawalStartEpoch = None,
              withdrawalEndEpoch = None,
              rewardAmount = rewardsBalance,
              totalBalance = total
            )
          }
      }

      withdrawals <- lastWithdrawals.traverse {
        case PendingDelegatedStakeWithdrawal(stake, bal, acceptedOrdinal, epochProgress) =>
          DelegatedStakeReference
            .of(stake)
            .map(ref => (stake, epochProgress, bal, ref, acceptedOrdinal))
      }
      pending <- withdrawals.traverse {
        case (stake, epochProgress, rewardsBalance, delegatedStakeRef, acceptedOrdinal) =>
          val totalAmountF = Async[F].fromEither(
            NonNegLong
              .from(rewardsBalance.value + stake.amount.value)
              .leftMap(err => new IllegalArgumentException(s"Failed to create non-negative total: $err"))
              .map(Amount(_))
          )

          totalAmountF.map { total =>
            DelegatedStakeInfo(
              nodeId = stake.nodeId,
              acceptedOrdinal = acceptedOrdinal,
              tokenLockRef = stake.tokenLockRef,
              amount = stake.amount,
              fee = stake.fee,
              hash = delegatedStakeRef.hash,
              withdrawalStartEpoch = epochProgress.some,
              withdrawalEndEpoch = (epochProgress |+| withdrawalTimeLimit).some,
              rewardAmount = rewardsBalance,
              totalBalance = total
            )
          }
      }
    } yield
      DelegatedStakesInfo(
        address = address,
        activeDelegatedStakes = active,
        pendingWithdrawals = pending
      )
  }

  private def getLastReference(
    address: Address,
    info: GlobalSnapshotInfo
  ): F[DelegatedStakeReference] =
    info.activeDelegatedStakes
      .getOrElse(SortedMap.empty[Address, List[DelegatedStakeRecord]])
      .get(address)
      .flatMap(stakes =>
        Option.when(stakes.nonEmpty)(stakes.maxBy { case DelegatedStakeRecord(delegatedStaking, _, _) => delegatedStaking.ordinal })
      )
      .traverse { case DelegatedStakeRecord(delegatedStaking, _, _) => DelegatedStakeReference.of(delegatedStaking) }
      .map(_.getOrElse(DelegatedStakeReference.empty))

  private def getRewardsInfo: F[RewardsInfo] =
    for {
      emConfig <- delegatedRewardsConfig.emissionConfig
        .get(environment)
        .pure[F]
        .flatMap(Async[F].fromOption(_, new RuntimeException(s"Could not retrieve emission config for env: $environment")))

      lastSnapshotInfo <- snapshotStorage.head.flatMap {
        case Some((_, info)) => info.pure[F]
        case None            => Async[F].raiseError[GlobalSnapshotInfo](new RuntimeException("No snapshot available"))
      }

      currentPrice = getCurrentDagPrice(emConfig)
      nextPrice = getNextDagPrice(emConfig)

      totalDelegated = calculateTotalDelegatedAmount(lastSnapshotInfo)

      avgRewardRate = calculateAverageRewardRate(lastSnapshotInfo)
      totalRewardPerEpoch = calculateTotalRewardPerEpoch(lastSnapshotInfo)

      totalRewardAPY = calculateTotalRewardAPYPerDag(emConfig, avgRewardRate)
      totalInflationAPY = calculateTotalInflationAPY(emConfig, totalRewardAPY)

    } yield
      RewardsInfo(
        epochsPerYear = emConfig.epochsPerYear.value,
        currentDagPrice = currentPrice,
        nextDagPrice = nextPrice,
        totalDelegatedAmount = totalDelegated,
        averageRewardRatePerDagEpoch = avgRewardRate,
        totalDagAmount = emConfig.totalSupply.value.value,
        totalRewardPerEpoch = totalRewardPerEpoch,
        totalRewardAPYPerDag = totalRewardAPY,
        totalInflationAPY = totalInflationAPY
      )

  private def getCurrentDagPrice(emConfig: EmissionConfigEntry): Long = {
    val dagPrices = emConfig.dagPrices
    val currentPrice = dagPrices.values.headOption.getOrElse(dagPrices.head._2)
    (currentPrice.toBigDecimal * 100000000).longValue // Convert to datum format
  }

  private def getNextDagPrice(emConfig: EmissionConfigEntry): NextDagPrice = {
    val dagPrices = emConfig.dagPrices

    if (dagPrices.size > 1) {
      val sortedPrices = dagPrices.toList.sortBy(_._1.value.value)
      val currentAndFuturePrices = sortedPrices.dropWhile {
        case (epoch, _) =>
          epoch.value.value <= sortedPrices.head._1.value.value
      }

      currentAndFuturePrices.headOption.map {
        case (epoch, price) =>
          NextDagPrice(
            price = (price.toBigDecimal * 100000000).longValue,
            asOfEpoch = epoch
          )
      }.getOrElse(
        NextDagPrice(
          price = (dagPrices.head._2.toBigDecimal * 100000000).longValue,
          asOfEpoch = dagPrices.head._1
        )
      )
    } else {
      NextDagPrice(
        price = (dagPrices.head._2.toBigDecimal * 100000000).longValue,
        asOfEpoch = dagPrices.head._1
      )
    }
  }

  // todo - is this actually the formula used? I think I may just use the stake balance and not add the rewards (check compounding)
  private def calculateTotalDelegatedAmount(info: GlobalSnapshotInfo): Long = {
    val activeDelegatedStakes = info.activeDelegatedStakes
      .getOrElse(SortedMap.empty[Address, List[DelegatedStakeRecord]])

    activeDelegatedStakes.values.flatten.foldLeft(0L) {
      case (acc, record) =>
        val stakeAmount = record.event.value.amount.value.value
        val rewardsAmount = record.rewards.value
        acc + stakeAmount + rewardsAmount
    }
  }

  private def calculateAverageRewardRate(info: GlobalSnapshotInfo): Long = {
    val activeDelegatedStakes = info.activeDelegatedStakes
      .getOrElse(SortedMap.empty[Address, List[DelegatedStakeRecord]])

    if (activeDelegatedStakes.isEmpty) 0L
    else {
      val totalStakeAmount = activeDelegatedStakes.values.flatten.foldLeft(0L) {
        case (acc, record) =>
          acc + record.event.value.amount.value.value
      }

      val totalRewards = activeDelegatedStakes.values.flatten.foldLeft(0L) {
        case (acc, record) =>
          acc + record.rewards.value
      }

      if (totalStakeAmount == 0) 0L
      else (BigDecimal(totalRewards) / BigDecimal(totalStakeAmount)).setScale(0, RoundingMode.HALF_UP).longValue
    }
  }

  private def calculateTotalRewardPerEpoch(info: GlobalSnapshotInfo): Long = {
    val totalDelegated = calculateTotalDelegatedAmount(info)
    val avgRewardRate = calculateAverageRewardRate(info)

    if (totalDelegated < 0) 0L
    else (BigDecimal(totalDelegated) * BigDecimal(avgRewardRate)).setScale(8, RoundingMode.HALF_UP).longValue
  }

  private def calculateTotalRewardAPYPerDag(emConfig: EmissionConfigEntry, averageRewardRate: Long): Long =
    averageRewardRate * emConfig.epochsPerYear.value

  private def calculateTotalInflationAPY(emConfig: EmissionConfigEntry, totalRewardAPYPerDag: Long): Long = {
    // (Total reward APY + flat inflation rate ) * total supply
    val result = BigDecimal(emConfig.totalSupply.value.value) *
      (BigDecimal(totalRewardAPYPerDag) + delegatedRewardsConfig.flatInflationRate.toBigDecimal)

    result.setScale(8, RoundingMode.HALF_UP).longValue
  }

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root =>
      snapshotStorage.head.flatMap {
        case None => ServiceUnavailable()
        case Some((_, info)) =>
          for {
            signed <- req.as[Signed[UpdateDelegatedStake.Create]]
            result <- validator.validateCreateDelegatedStake(signed, info)
            response <- result match {
              case Valid(validSigned) =>
                logger.info(s"Accepted create delegated stake from ${validSigned.proofs.map(_.id).map(PeerId.fromId)}") >>
                  mkCell(CreateDelegatedStakeOutput(validSigned)).run().flatMap {
                    case Right(_) => validSigned.toHashed.flatMap(hashed => Ok(("hash" ->> hashed.hash) :: HNil))
                    case Left(_)  => InternalServerError("Failed to update cell.")
                  }

              case Invalid(errors) =>
                logger.warn(s"Invalid create delegated stake: $errors") >>
                  BadRequest(errors.mkString_("\n"))
            }
          } yield response
      }

    case req @ PUT -> Root =>
      snapshotStorage.head.flatMap {
        case None => ServiceUnavailable()
        case Some((_, info)) =>
          for {
            signed <- req.as[Signed[UpdateDelegatedStake.Withdraw]]
            result <- validator.validateWithdrawDelegatedStake(signed, info)
            response <- result match {
              case Valid(validSigned) =>
                logger.info(s"Accepted withdraw delegated stake from ${validSigned.proofs.map(_.id).map(PeerId.fromId)}") >>
                  mkCell(WithdrawDelegatedStakeOutput(validSigned)).run().flatMap {
                    case Right(_) =>
                      validSigned.toHashed.flatMap(hashed => Ok(("hash" ->> hashed.hash) :: HNil))
                    case Left(_) =>
                      InternalServerError("Failed to update cell.")
                  }

              case Invalid(errors) =>
                logger.warn(s"Invalid withdraw delegated stake: $errors") >>
                  BadRequest(errors.mkString_("\n"))
            }
          } yield response
      }

    case GET -> Root / AddressVar(address) / "info" =>
      snapshotStorage.head.flatMap {
        case Some((_, info)) =>
          Ok(getDelegatedStakesInfo(address, info))
        case None => ServiceUnavailable()
      }

    case GET -> Root / "last-reference" / AddressVar(address) =>
      snapshotStorage.head.flatMap {
        case Some((_, info)) =>
          Ok(getLastReference(address, info))
        case None => ServiceUnavailable()
      }

    case GET -> Root / "rewards-info" =>
      snapshotStorage.head.flatMap {
        case Some(_) =>
          getRewardsInfo.flatMap(Ok(_))
        case None => ServiceUnavailable()
      }
  }
}
