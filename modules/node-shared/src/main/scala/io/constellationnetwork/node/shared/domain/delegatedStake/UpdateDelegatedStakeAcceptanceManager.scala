package io.constellationnetwork.node.shared.domain.delegatedStake

import cats.data.NonEmptyChain
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeValidator._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeRecord, DelegatedStakeReference, UpdateDelegatedStake}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
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

  private type CreateDelegatedStakeAcceptanceResult = (
    (
      List[Signed[UpdateDelegatedStake.Create]],
      List[(Signed[UpdateDelegatedStake.Create], NonEmptyChain[UpdateDelegatedStakeValidationError])]
    ),
    Set[DelegatedStakeReference],
    Set[Hash]
  )

  private type WithdrawDelegatedStakeAcceptanceResult = (
    (
      List[Signed[UpdateDelegatedStake.Withdraw]],
      List[(Signed[UpdateDelegatedStake.Withdraw], NonEmptyChain[UpdateDelegatedStakeValidationError])]
    ),
    Set[Hash]
  )

  def make[F[_]: Async: SecurityProvider](validator: UpdateDelegatedStakeValidator[F]) =
    new UpdateDelegatedStakeAcceptanceManager[F] {
      def accept(
        creates: List[Signed[UpdateDelegatedStake.Create]],
        withdrawals: List[Signed[UpdateDelegatedStake.Withdraw]],
        lastSnapshotContext: GlobalSnapshotInfo,
        currentGlobalEpochProgress: EpochProgress,
        currentSnapshotOrdinal: SnapshotOrdinal
      ): F[UpdateDelegatedStakeAcceptanceResult] =
        for {
          ((acceptedCreates, notAcceptedCreates), _, _) <- creates.foldLeftM[F, CreateDelegatedStakeAcceptanceResult](
            ((List.empty, List.empty), Set.empty, Set.empty)
          ) { (acc, signed) =>
            val ((accepted, rejected), parentRefsSeen, tokenLockRefsSeen) = acc
            validator.validateCreateDelegatedStake(signed, lastSnapshotContext).map { validated =>
              val res = validated match {
                case Valid(a) =>
                  if (parentRefsSeen(signed.parent)) {
                    (accepted, (signed, NonEmptyChain.of(DuplicatedParent(signed.parent))) :: rejected)
                  } else if (tokenLockRefsSeen(signed.tokenLockRef)) {
                    (accepted, (signed, NonEmptyChain.of(DuplicatedTokenLock(signed.tokenLockRef))) :: rejected)
                  } else {
                    (a :: accepted, rejected)
                  }
                case Invalid(e) => (accepted, (signed, e) :: rejected)
              }
              (res, parentRefsSeen + signed.parent, tokenLockRefsSeen + signed.tokenLockRef)
            }
          }
          ((acceptedWithdrawals, notAcceptedWithdrawals), _) <- withdrawals.foldLeftM[F, WithdrawDelegatedStakeAcceptanceResult](
            ((List.empty, List.empty), Set.empty)
          ) { (acc, signed) =>
            val ((accepted, rejected), stakeRefsSeen) = acc
            validator.validateWithdrawDelegatedStake(signed, lastSnapshotContext).map { validated =>
              val res = validated match {
                case Valid(a) =>
                  if (stakeRefsSeen(signed.stakeRef)) {
                    (accepted, (signed, NonEmptyChain.of(DuplicatedStake(signed.stakeRef))) :: rejected)
                  } else {
                    (a :: accepted, rejected)
                  }
                case Invalid(e) => (accepted, (signed, e) :: rejected)
              }
              (res, stakeRefsSeen + signed.stakeRef)
            }
          }

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
