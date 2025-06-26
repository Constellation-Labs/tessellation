package io.constellationnetwork.dag.l0.http.routes

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.math.BigDecimal.RoundingMode

import io.constellationnetwork.dag.l0.domain.delegatedStake.{CreateDelegatedStakeOutput, DelegatedStakeOutput, WithdrawDelegatedStakeOutput}
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.ext.http4s.AddressVar
import io.constellationnetwork.kernel._
import io.constellationnetwork.node.shared.config.types.{DelegatedRewardsConfig, EmissionConfigEntry}
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeValidator
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot.DelegatedRewardsDistributor
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.utils.DecimalUtils
import io.constellationnetwork.utils.DecimalUtils.syntax._

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import io.circe.shapes._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger
import shapeless._
import shapeless.syntax.singleton._

import AmountOps._

final case class DelegatedStakesRoutes[F[_]: Async: Hasher](
  mkCell: DelegatedStakeOutput => Cell[F, StackF, _, Either[CellError, Ω], _],
  validator: UpdateDelegatedStakeValidator[F],
  snapshotStorage: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  withdrawalTimeLimit: EpochProgress,
  delegatedRewardsDistributor: DelegatedRewardsDistributor[F]
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

  private val logger = Slf4jLogger.getLoggerFromName[F]("DelegatedStakesLogger")

  protected val prefixPath: InternalUrlPrefix = "/delegated-stakes"

  private def getDelegatedStakesInfo(address: Address, info: GlobalSnapshotInfo): F[DelegatedStakesInfo] = {
    val lastStakes: SortedSet[DelegatedStakeRecord] =
      info.activeDelegatedStakes
        .getOrElse(SortedMap.empty[Address, SortedSet[DelegatedStakeRecord]])
        .getOrElse(address, SortedSet.empty)
    val lastWithdrawals: SortedSet[PendingDelegatedStakeWithdrawal] =
      info.delegatedStakesWithdrawals
        .getOrElse(SortedMap.empty[Address, SortedSet[PendingDelegatedStakeWithdrawal]])
        .getOrElse(address, SortedSet.empty)

    for {
      stakes <- lastStakes.toList.traverse {
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

      withdrawals <- lastWithdrawals.toList.traverse {
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

  private def toAmount(value: Long): F[Amount] =
    if (value == 0L) Amount.empty.pure[F]
    else
      PosLong
        .from(value)
        .pure[F]
        .map(_.leftMap(new IllegalArgumentException(_)))
        .flatMap(Async[F].fromEither(_))
        .map(Amount(_))

  private def getRewardsInfo: F[RewardsInfo] =
    for {
      latestRewardOutput <- delegatedRewardsDistributor.getKnownRewardsTicks
        .map(_.headOption)
        .flatMap(Async[F].fromOption(_, new RuntimeException(s"Could not retrieve latest reward output")))
      result <- for {
        emissionConfig <- delegatedRewardsDistributor.getEmissionConfig(latestRewardOutput.ep)
        nextPrice <- getNextDagPrice(emissionConfig, latestRewardOutput.ep)
        avgRewardAmount <- calculateAverageReward(latestRewardOutput.totalRewardPerEpoch, latestRewardOutput.totalDelegatedAmount)
        totalRewardsPerYear <- calculateAverageRewardOverAYear(avgRewardAmount, emissionConfig.epochsPerYear)
      } yield
        RewardsInfo(
          epochsPerYear = emissionConfig.epochsPerYear,
          currentDagPrice = latestRewardOutput.currentDagPrice,
          nextDagPrice = nextPrice,
          totalDelegatedAmount = latestRewardOutput.totalDelegatedAmount,
          latestAverageRewardPerDag = avgRewardAmount,
          totalDagAmount = latestRewardOutput.totalDagAmount,
          totalRewardPerEpoch = latestRewardOutput.totalRewardPerEpoch,
          totalRewardsPerYearEstimate = totalRewardsPerYear
        )
    } yield result

  private def getNextDagPrice(emConfig: EmissionConfigEntry, epochProgress: EpochProgress): F[NextDagPrice] = {
    val dagPrices = emConfig.dagPrices

    if (dagPrices.isEmpty) {
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
    } else {
      val sortedPrices = dagPrices.toList.sortBy(_._1.value.value)

      val priceValue = (sortedPrices.head._2.toBigDecimal * DecimalUtils.DATUM_USD).setScale(0, RoundingMode.HALF_UP).longValue

      val defaultPriceF: F[NextDagPrice] =
        if (priceValue <= 0) {
          NextDagPrice(
            price = Amount.empty,
            asOfEpoch = sortedPrices.head._1
          ).pure[F]
        } else {
          PosLong
            .from(priceValue)
            .fold(
              err => Async[F].raiseError(new IllegalArgumentException(s"Failed to create positive price: $err")),
              posLong =>
                NextDagPrice(
                  price = Amount(posLong),
                  asOfEpoch = sortedPrices.head._1
                ).pure[F]
            )
        }

      val maybeNext = sortedPrices.find {
        case (epoch, _) => epoch.value.value > epochProgress.value.value
      }

      maybeNext match {
        case Some((epoch, price)) =>
          toAmount((price.toBigDecimal * DecimalUtils.DATUM_USD).setScale(0, RoundingMode.HALF_UP).longValue).map { amt =>
            NextDagPrice(price = amt, asOfEpoch = epoch)
          }
        case None => defaultPriceF
      }
    }
  }

  private def calculateAverageReward(latestRewards: Amount, totalStakedAmount: Amount): F[Amount] =
    if (totalStakedAmount.value.value === 0) Amount.empty.pure[F]
    else
      toAmount(
        (BigDecimal(latestRewards.value.value) / BigDecimal(totalStakedAmount.value.value))
          .setScale(0, RoundingMode.HALF_UP)
          .longValue
      )

  private def calculateAverageRewardOverAYear(avgReward: Amount, epochsPerYear: PosLong): F[Amount] =
    toAmount(
      (BigDecimal(avgReward.value.value) * BigDecimal(epochsPerYear.value))
        .setScale(0, RoundingMode.HALF_UP)
        .longValue
    )

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
      getRewardsInfo
        .flatMap(Ok(_))
        .handleErrorWith { error =>
          logger.error(error)(s"Error getting rewards info: ${error.getMessage}") >>
            InternalServerError(s"Failed to get rewards info: ${error.getMessage}")
        }
  }
}
