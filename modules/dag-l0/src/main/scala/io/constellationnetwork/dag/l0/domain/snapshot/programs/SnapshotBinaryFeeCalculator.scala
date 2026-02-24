package io.constellationnetwork.dag.l0.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.StateChannelEvent
import io.constellationnetwork.node.shared.domain.statechannel.{FeeCalculator, FeeCalculatorConfig}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.currencyMessage.fetchStakingAddress
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}

import eu.timepit.refined.types.numeric.{NonNegInt, NonNegLong}

trait SnapshotBinaryFeeCalculator[F[_]] {
  def calculateFee(event: StateChannelEvent, ordinal: SnapshotOrdinal): F[NonNegLong]
}

object SnapshotBinaryFeeCalculator {
  def make[F[_]: Async](
    configs: SortedMap[SnapshotOrdinal, FeeCalculatorConfig],
    mptStore: MptStore[F, GlobalStateKey]
  ): SnapshotBinaryFeeCalculator[F] =
    make(FeeCalculator.make[F](configs), mptStore)

  // Staking balance note: for metagraphs with only a full snapshot (Left case in the old
  // lastCurrencySnapshots), getCurrencySnapshotInfo returns CurrencySnapshotInfo with
  // lastMessages = None (via toCurrencySnapshotInfo), so fetchStakingAddress returns None
  // and we get Balance.empty — same as old fetchStakingBalance which returned Balance.empty
  // for Left(fullSnapshot).
  def make[F[_]: Async](
    feeCalculator: FeeCalculator[F],
    mptStore: MptStore[F, GlobalStateKey]
  ): SnapshotBinaryFeeCalculator[F] =
    (event: StateChannelEvent, ordinal: SnapshotOrdinal) =>
      for {
        maybeCurrencyInfo <- mptStore.getCurrencySnapshotInfo(event.value.address)
        stakingAddr = maybeCurrencyInfo.flatMap(fetchStakingAddress)
        balance <- stakingAddr.fold(Balance.empty.pure[F]) { addr =>
          mptStore.getBalance(addr).map(_.getOrElse(Balance.empty))
        }
        result <- {
          val binary = event.value.snapshotBinary.value
          val kbytes = NonNegInt.unsafeFrom(binary.content.length / 1024)
          feeCalculator
            .calculateRecommendedFee(Some(ordinal))(balance, kbytes, binary.fee.value)
            .map(_.value)
        }
      } yield result
}
