package io.constellationnetwork.dag.l0.domain.statechannel

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.kernel.Async
import cats.syntax.all._

import io.constellationnetwork.dag.l0.domain.cell.{L0Cell, L0CellInput}
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelValidator.{StateChannelValidationError, getFeeAddresses}
import io.constellationnetwork.node.shared.domain.statechannel.{SnapshotFeesInfo, StateChannelValidator}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.currencyMessage.{fetchMetagraphFeesAddresses, fetchStakingBalance}
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.StateChannelOutput
trait StateChannelService[F[_]] {
  def process(
    stateChannel: StateChannelOutput,
    globalSnapshotAndState: (Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)
  )(implicit hasher: Hasher[F]): F[Either[NonEmptyList[StateChannelValidationError], Unit]]
}

object StateChannelService {

  def make[F[_]: Async](
    mkDagCell: L0Cell.Mk[F],
    stateChannelValidator: StateChannelValidator[F]
  ): StateChannelService[F] =
    new StateChannelService[F] {

      def process(
        stateChannelOutput: StateChannelOutput,
        globalSnapshotAndState: (Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)
      )(implicit hasher: Hasher[F]): F[Either[NonEmptyList[StateChannelValidationError], Unit]] = {
        val (snapshot, state) = globalSnapshotAndState
        val staked = fetchStakingBalance(stateChannelOutput.address, state)
        val (ownerAddress, stakingAddress) = fetchMetagraphFeesAddresses(stateChannelOutput.address, state)
        val allFeesAddresses: Map[Address, Set[Address]] = getFeeAddresses(state)
        val snapshotFeesInfo = SnapshotFeesInfo(allFeesAddresses, staked, ownerAddress, stakingAddress)

        for {
          validations <- stateChannelValidator.validate(stateChannelOutput, snapshot.ordinal, snapshotFeesInfo)
          result <- validations match {
            case Valid(_) =>
              mkDagCell(L0CellInput.HandleStateChannelSnapshot(stateChannelOutput))
                .run()
                .as(().asRight[NonEmptyList[StateChannelValidationError]])
            case Invalid(errors) => errors.toNonEmptyList.asLeft[Unit].pure[F]
          }
        } yield result
      }
    }
}
