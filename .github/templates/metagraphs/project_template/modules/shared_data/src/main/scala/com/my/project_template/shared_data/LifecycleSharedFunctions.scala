package com.my.project_template.shared_data

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataState
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.TokenUnlock
import io.constellationnetwork.security.signature.Signed

import com.my.project_template.shared_data.combiners.Combiners.combineUpdateUsage
import com.my.project_template.shared_data.types.Types._

object LifecycleSharedFunctions {
  def combine[F[_]: Async](
    oldState: DataState[UsageUpdateState, UsageUpdateCalculatedState],
    updates: List[Signed[UsageUpdate]],
    metagraphId: Address
  ): F[DataState[UsageUpdateState, UsageUpdateCalculatedState]] = {
    val newState = DataState(
      UsageUpdateState(List.empty),
      UsageUpdateCalculatedState(oldState.calculated.devices),
      oldState.sharedArtifacts ++ updates.map(_.value).collect {
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
      newState.pure
    } else {
      Async[F].delay(updates.foldLeft(newState) { (acc, signedUpdate) =>
        combineUpdateUsage(signedUpdate, acc, metagraphId)
      })
    }
  }
}
