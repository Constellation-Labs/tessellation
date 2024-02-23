package com.my.project_template.shared_data.calculated_state

import cats.effect.Ref
import cats.effect.kernel.Async
import cats.syntax.all._
import com.my.project_template.shared_data.types.Types.UsageUpdateCalculatedState
import io.circe.syntax.EncoderOps
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.security.hash.Hash

trait CalculatedStateService[F[_]] {
  def getCalculatedState: F[CalculatedState]

  def setCalculatedState(
    snapshotOrdinal: SnapshotOrdinal,
    state          : UsageUpdateCalculatedState
  ): F[Boolean]

  def hashCalculatedState(
    state: UsageUpdateCalculatedState
  ): F[Hash]
}

object CalculatedStateService {
  def make[F[_] : Async]: F[CalculatedStateService[F]] = {
    Ref.of[F, CalculatedState](CalculatedState.empty).map { stateRef =>
      new CalculatedStateService[F] {
        override def getCalculatedState: F[CalculatedState] = stateRef.get

        override def setCalculatedState(
          snapshotOrdinal: SnapshotOrdinal,
          state          : UsageUpdateCalculatedState
        ): F[Boolean] =
          stateRef.modify { currentState =>
            val devices = currentState.state.devices ++ state.devices
            CalculatedState(snapshotOrdinal, UsageUpdateCalculatedState(devices)) -> true
          }

        override def hashCalculatedState(
          state: UsageUpdateCalculatedState
        ): F[Hash] = Async[F].delay {
          Hash(state.asJson.noSpaces)
        }
      }
    }
  }
}
