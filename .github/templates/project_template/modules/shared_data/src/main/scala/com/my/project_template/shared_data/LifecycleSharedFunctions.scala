package com.my.project_template.shared_data

import cats.effect.Async
import cats.syntax.all._
import com.my.project_template.shared_data.combiners.Combiners.combineUpdateUsage
import com.my.project_template.shared_data.types.Types.{UsageUpdate, UsageUpdateCalculatedState, UsageUpdateState}
import org.tessellation.currency.dataApplication.DataState
import org.tessellation.security.signature.Signed

object LifecycleSharedFunctions {
  def combine[F[_] : Async](
    oldState: DataState[UsageUpdateState, UsageUpdateCalculatedState],
    updates : List[Signed[UsageUpdate]]
  ): F[DataState[UsageUpdateState, UsageUpdateCalculatedState]] = {
    val newState = DataState(
      UsageUpdateState(List.empty),
      UsageUpdateCalculatedState(oldState.calculated.devices)
    )

    if (updates.isEmpty) {
      newState.pure
    } else {
      Async[F].delay(updates.foldLeft(newState) { (acc, signedUpdate) =>
        combineUpdateUsage(signedUpdate, acc)
      }
      )
    }
  }
}