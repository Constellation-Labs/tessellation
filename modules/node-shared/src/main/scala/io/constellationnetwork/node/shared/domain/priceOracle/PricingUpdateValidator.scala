package io.constellationnetwork.node.shared.domain.priceOracle

import cats.data.{Validated, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.domain.priceOracle.PricingUpdateValidator.{
  PricingUpdateValidationError,
  PricingUpdateValidationErrorOr
}
import io.constellationnetwork.schema.GlobalSnapshotInfo
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.PricingUpdate
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.priceOracle.{PriceRecord, TokenPair}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto.autoUnwrap
import eu.timepit.refined.types.numeric.NonNegLong

trait PricingUpdateValidator[F[_]] {
  def validate(
    pricingUpdate: PricingUpdate,
    currencyId: Address,
    lastContext: GlobalSnapshotInfo,
    epochProgress: EpochProgress
  ): F[PricingUpdateValidationErrorOr[PricingUpdate]]

  def validateReturningAcceptedAndRejected(
    pricingUpdates: Map[Address, List[PricingUpdate]],
    lastContext: GlobalSnapshotInfo,
    epochProgress: EpochProgress
  ): F[(List[PricingUpdate], List[(PricingUpdate, List[PricingUpdateValidationError])])]
}

object PricingUpdateValidator {
  def make[F[_]: Async](allowedMetagraphIds: Option[List[Address]], minEpochsBetweenUpdates: NonNegLong): PricingUpdateValidator[F] =
    new PricingUpdateValidator[F] {

      override def validate(
        pricingUpdate: PricingUpdate,
        metagraphId: Address,
        lastContext: GlobalSnapshotInfo,
        epochProgress: EpochProgress
      ): F[PricingUpdateValidationErrorOr[PricingUpdate]] =
        for {
          metagraphIdR <- validatedMetagraphId(pricingUpdate, metagraphId).pure[F]
          epochProgressR = validateEpochProgress(pricingUpdate, lastContext, epochProgress)
        } yield metagraphIdR.productR(epochProgressR)

      override def validateReturningAcceptedAndRejected(
        pricingUpdates: Map[Address, List[PricingUpdate]],
        lastContext: GlobalSnapshotInfo,
        epochProgress: EpochProgress
      ): F[(List[PricingUpdate], List[(PricingUpdate, List[PricingUpdateValidationError])])] = {
        def processUpdatesForCurrency(
          currencyId: Address,
          updates: List[PricingUpdate]
        ): F[(List[PricingUpdate], List[(PricingUpdate, List[PricingUpdateValidationError])])] =
          updates
            .foldLeftM((List.empty[PricingUpdate], List.empty[(PricingUpdate, List[PricingUpdateValidationError])])) {
              case ((accepted, rejected), update) =>
                validate(update, currencyId, lastContext, epochProgress).map {
                  case Validated.Valid(a)   => (a :: accepted, rejected)
                  case Validated.Invalid(e) => (accepted, (update, e.toList) :: rejected)
                }
            }
            .map {
              case (accepted, rejected) =>
                (accepted.reverse, rejected.reverse)
            }

        pricingUpdates.keys.toList
          .foldLeftM((List.empty[PricingUpdate], List.empty[(PricingUpdate, List[PricingUpdateValidationError])])) {
            case ((accepted, rejected), currencyId) =>
              processUpdatesForCurrency(currencyId, pricingUpdates(currencyId)).map {
                case (a, r) => (accepted ++ a, rejected ++ r)
              }
          }
      }

      private def validatedMetagraphId(pricingUpdate: PricingUpdate, metagraphId: Address): PricingUpdateValidationErrorOr[PricingUpdate] =
        if (allowedMetagraphIds.isEmpty || allowedMetagraphIds.exists(_.contains(metagraphId))) {
          pricingUpdate.validNec[PricingUpdateValidationError]
        } else {
          UnauthorizedMetagraph(metagraphId).invalidNec
        }

      private def validateEpochProgress(
        pricingUpdate: PricingUpdate,
        lastContext: GlobalSnapshotInfo,
        currentEpoch: EpochProgress
      ): PricingUpdateValidationErrorOr[PricingUpdate] = {
        val lastEpoch = lastContext.priceState
          .getOrElse(SortedMap.empty[TokenPair, PriceRecord])
          .get(pricingUpdate.tokenPair)
          .map(_.updatedAt)
          .getOrElse(EpochProgress.MinValue)
        currentEpoch.minus(lastEpoch) match {
          case Right(diff) =>
            if (diff.value >= minEpochsBetweenUpdates) {
              pricingUpdate.validNec[PricingUpdateValidationError]
            } else {
              TooFrequentUpdate(lastEpoch, currentEpoch).invalidNec
            }
          case Left(_) => EpochUnderflow(lastEpoch, currentEpoch).invalidNec
        }
      }
    }

  @derive(eqv, show)
  sealed trait PricingUpdateValidationError

  case class UnauthorizedMetagraph(metagraphId: Address) extends PricingUpdateValidationError
  case class TooFrequentUpdate(lastEpoch: EpochProgress, currentEpoch: EpochProgress) extends PricingUpdateValidationError
  case class EpochUnderflow(lastEpoch: EpochProgress, currentEpoch: EpochProgress) extends PricingUpdateValidationError

  type PricingUpdateValidationErrorOr[A] = ValidatedNec[PricingUpdateValidationError, A]
}
