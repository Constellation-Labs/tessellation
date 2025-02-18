package io.constellationnetwork.node.shared.domain.nodeCollateral

import cats.data.NonEmptyChain
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.MapView

import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceResult
import io.constellationnetwork.node.shared.domain.nodeCollateral.UpdateNodeCollateralValidator.{
  UpdateNodeCollateralValidationError,
  UpdateNodeCollateralValidationErrorOr
}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.delegatedStake.UpdateDelegatedStake
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.nodeCollateral.UpdateNodeCollateral
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.syntax.sortedCollection.sortedMapSyntax

import eu.timepit.refined.types.numeric.NonNegLong

trait UpdateNodeCollateralAcceptanceManager[F[_]] {

  def accept(
    creates: List[Signed[UpdateNodeCollateral.Create]],
    withdrawals: List[Signed[UpdateNodeCollateral.Withdraw]],
    lastSnapshotContext: GlobalSnapshotInfo,
    lastGlobalEpochProgress: EpochProgress,
    lastSnapshotOrdinal: SnapshotOrdinal,
    updateDelegatedStakeAcceptanceResult: UpdateDelegatedStakeAcceptanceResult
  ): F[UpdateNodeCollateralAcceptanceResult]

}

object UpdateNodeCollateralAcceptanceManager {
  def make[F[_]: Async: SecurityProvider](validator: UpdateNodeCollateralValidator[F]) =
    new UpdateNodeCollateralAcceptanceManager[F] {
      def accept(
        creates: List[Signed[UpdateNodeCollateral.Create]],
        withdrawals: List[Signed[UpdateNodeCollateral.Withdraw]],
        lastSnapshotContext: GlobalSnapshotInfo,
        lastGlobalEpochProgress: EpochProgress,
        lastSnapshotOrdinal: SnapshotOrdinal,
        updateDelegatedStakeAcceptanceResult: UpdateDelegatedStakeAcceptanceResult
      ): F[UpdateNodeCollateralAcceptanceResult] = {

        def partitionAccepted[A](validated: List[UpdateNodeCollateralValidationErrorOr[A]], signed: List[A]) =
          validated
            .zip(signed)
            .foldLeft(
              (
                List.empty[A],
                List.empty[(A, NonEmptyChain[UpdateNodeCollateralValidationError])]
              )
            ) {
              case ((accepted, notAccepted), (validated, signed)) =>
                validated match {
                  case Valid(a)   => (a :: accepted, notAccepted)
                  case Invalid(e) => (accepted, (signed, e) :: notAccepted)
                }
            }

        for {
          validatedCreates <- creates.traverse(signed => validator.validateCreateNodeCollateral(signed, lastSnapshotContext))
          validatedWithdrawals <- withdrawals.traverse(signed => validator.validateWithdrawNodeCollateral(signed, lastSnapshotContext))
          (acceptedCreates, notAcceptedCreates) = partitionAccepted(validatedCreates, creates)
          (acceptedWithdrawals, notAcceptedWithdrawals) = partitionAccepted(validatedWithdrawals, withdrawals)

          delegatedStakeTokenLockReferences = updateDelegatedStakeAcceptanceResult.acceptedCreates.values
            .flatMap(_.map(_._1.tokenLockRef))
            .toSet

          acceptedCreatesMap <- acceptedCreates
            .filterNot(c => delegatedStakeTokenLockReferences(c.tokenLockRef))
            .map(c => (c, lastSnapshotOrdinal))
            .traverse { case (signed, ord) => signed.proofs.head.id.toAddress.map((_, (signed, ord))) }
            .map(_.groupBy(_._1).view.mapValues(_.map(_._2)).toSortedMap)

          acceptedWithdrawalsMap <- acceptedWithdrawals
            .map(w => (w, lastSnapshotOrdinal))
            .traverse { case (signed, ord) => signed.proofs.head.id.toAddress.map((_, (signed, ord))) }
            .map(_.groupBy(_._1).view.mapValues(_.map(_._2)).toSortedMap)

        } yield
          UpdateNodeCollateralAcceptanceResult(
            acceptedCreatesMap,
            notAcceptedCreates,
            acceptedWithdrawalsMap,
            notAcceptedWithdrawals
          )
      }
    }
}
