package com.my.project_template.shared_data

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, FeeTransaction}
import io.constellationnetwork.schema.artifact.{SpendAction, TokenUnlock}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import com.my.project_template.shared_data.combiners.Combiners.combineUpdateUsage
import com.my.project_template.shared_data.types.Types._

object LifecycleSharedFunctions {
  def combine[F[_]: Async](
    oldState: DataState[UsageUpdateState, UsageUpdateCalculatedState],
    updates: List[Signed[UsageUpdate]],
    feeTransactions: Map[Hash, Signed[FeeTransaction]],
    serializeUpdate: UsageUpdate => F[Array[Byte]]
  ): F[DataState[UsageUpdateState, UsageUpdateCalculatedState]] = {
    val newState = DataState(
      UsageUpdateState(List.empty),
      UsageUpdateCalculatedState(oldState.calculated.devices),
      oldState.sharedArtifacts ++ updates.map(_.value).collect {
        case UsageUpdateWithSpendTransaction(_, _, spendTransactionA, spendTransactionB) =>
          SpendAction(NonEmptyList.of(spendTransactionA, spendTransactionB))
        case update: UsageUpdateWithTokenUnlock =>
          TokenUnlock(
            update.tokenLockRef,
            update.unlockAmount,
            update.currencyId.some,
            update.address
          )
      }
    )

    if (updates.isEmpty) {
      newState.pure[F]
    } else {
      updates.foldLeftM(newState) { (acc, signedUpdate) =>
        // A fee is submitted as a sibling transaction keyed by dataUpdateRef, which the fee estimate
        // route computes as EstimatedFee.getUpdateHash = Hash.fromBytesForSync(serializeUpdate(update)).
        // The only way combine can observe that fee is L0NodeContext.getSnapshotFeeTransactions, whose
        // result is threaded in here as `feeTransactions`. Recompute the identical key to match it.
        serializeUpdate(signedUpdate.value).flatMap(Hash.fromBytesForSync[F]).map { updateHash =>
          val feePaid = feeTransactions
            .get(updateHash)
            .map(_.value.amount.value.value)
            .getOrElse(0L)
          combineUpdateUsage(signedUpdate, feePaid, acc)
        }
      }
    }
  }
}
