package io.constellationnetwork.node.shared.domain.delegatedStake

import cats.data.NonEmptyChain
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeValidator.{
  UpdateDelegatedStakeValidationError,
  UpdateDelegatedStakeValidationErrorOr
}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeRecord, UpdateDelegatedStake}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.syntax.sortedCollection.sortedMapSyntax

import eu.timepit.refined.types.numeric.NonNegLong

trait UpdateDelegatedStakeAcceptanceManager[F[_]] {

  def accept(
    creates: List[Signed[UpdateDelegatedStake.Create]],
    withdrawals: List[Signed[UpdateDelegatedStake.Withdraw]],
    lastSnapshotContext: GlobalSnapshotInfo,
    currentGlobalEpochProgress: EpochProgress,
    currentSnapshotOrdinal: SnapshotOrdinal
  ): F[UpdateDelegatedStakeAcceptanceResult]

}

object UpdateDelegatedStakeAcceptanceManager {
  def make[F[_]: Async: SecurityProvider](validator: UpdateDelegatedStakeValidator[F]) =
    new UpdateDelegatedStakeAcceptanceManager[F] {
      def accept(
        creates: List[Signed[UpdateDelegatedStake.Create]],
        withdrawals: List[Signed[UpdateDelegatedStake.Withdraw]],
        lastSnapshotContext: GlobalSnapshotInfo,
        currentGlobalEpochProgress: EpochProgress,
        currentSnapshotOrdinal: SnapshotOrdinal
      ): F[UpdateDelegatedStakeAcceptanceResult] = {

        def partitionAccepted[A](validated: List[UpdateDelegatedStakeValidationErrorOr[A]], signed: List[A]) =
          validated
            .zip(signed)
            .foldLeft(
              (
                List.empty[A],
                List.empty[(A, NonEmptyChain[UpdateDelegatedStakeValidationError])]
              )
            ) {
              case ((accepted, notAccepted), (validated, signed)) =>
                validated match {
                  case Valid(a)   => (a :: accepted, notAccepted)
                  case Invalid(e) => (accepted, (signed, e) :: notAccepted)
                }
            }

        for {
          validatedCreates <- creates.traverse(signed => validator.validateCreateDelegatedStake(signed, lastSnapshotContext))
          validatedWithdrawals <- withdrawals.traverse(signed => validator.validateWithdrawDelegatedStake(signed, lastSnapshotContext))
          (acceptedCreates, notAcceptedCreates) = partitionAccepted(validatedCreates, creates)
          (acceptedWithdrawals, notAcceptedWithdrawals) = partitionAccepted(validatedWithdrawals, withdrawals)

          acceptedCreatesMap <- acceptedCreates
            .map(c => (c, currentSnapshotOrdinal))
            .traverse { case (signed, ord) => signed.proofs.head.id.toAddress.map((_, (signed, ord))) }
            .map(_.groupBy(_._1).view.mapValues(_.map(_._2)).toSortedMap)

          acceptedWithdrawalsMap <- acceptedWithdrawals
            .map(w => (w, currentGlobalEpochProgress))
            .traverse { case (signed, epoch) => signed.proofs.head.id.toAddress.map((_, (signed, epoch))) }
            .map(_.groupBy(_._1).view.mapValues(_.map(_._2)).toSortedMap)

        } yield
          UpdateDelegatedStakeAcceptanceResult(
            acceptedCreatesMap,
            notAcceptedCreates,
            acceptedWithdrawalsMap,
            notAcceptedWithdrawals
          )
      }
    }
}
