package io.constellationnetwork.dag.l0.domain.statechannel

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.kernel.Async
import cats.syntax.all._

import io.constellationnetwork.dag.l0.domain.cell.{L0Cell, L0CellInput}
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelValidator.{StateChannelValidationError, getFeeAddresses}
import io.constellationnetwork.node.shared.domain.statechannel.{SnapshotFeesInfo, StateChannelValidator}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.currencyMessage.{fetchOwnerAddress, fetchStakingAddress}
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
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
    stateChannelValidator: StateChannelValidator[F],
    mptStore: MptStore[F, GlobalStateKey]
  ): StateChannelService[F] =
    new StateChannelService[F] {

      def process(
        stateChannelOutput: StateChannelOutput,
        globalSnapshotAndState: (Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)
      )(implicit hasher: Hasher[F]): F[Either[NonEmptyList[StateChannelValidationError], Unit]] = {
        val (snapshot, state) = globalSnapshotAndState
        // Note: getFeeAddresses still reads from state (GlobalSnapshotInfo) directly because it
        // iterates all lastCurrencySnapshots to collect fee addresses — a bulk operation not suited
        // for per-key MptStore lookups. The staking/owner balance lookups below use MptStore.
        val allFeesAddresses: Map[Address, Set[Address]] = getFeeAddresses(state)

        // Staking balance note: for full-snapshot-only metagraphs, getCurrencySnapshotInfo
        // returns CurrencySnapshotInfo with lastMessages = None (via toCurrencySnapshotInfo),
        // so fetchStakingAddress returns None → Balance.empty, matching old behavior.
        for {
          maybeCurrencyInfo <- mptStore.getCurrencySnapshotInfo(stateChannelOutput.address)

          stakingAddr = maybeCurrencyInfo.flatMap(fetchStakingAddress)
          ownerAddr = maybeCurrencyInfo.flatMap(fetchOwnerAddress)

          staked <- stakingAddr.fold(Balance.empty.pure[F]) { addr =>
            mptStore.getBalance(addr).map(_.getOrElse(Balance.empty))
          }

          snapshotFeesInfo = SnapshotFeesInfo(allFeesAddresses, staked, ownerAddr, stakingAddr)

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
