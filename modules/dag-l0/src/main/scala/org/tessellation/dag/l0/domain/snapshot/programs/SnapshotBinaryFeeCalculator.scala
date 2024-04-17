package org.tessellation.dag.l0.domain.snapshot.programs

import cats.MonadThrow
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.collection.immutable.SortedMap

import org.tessellation.dag.l0.infrastructure.snapshot.StateChannelEvent
import org.tessellation.node.shared.domain.statechannel.{FeeCalculator, FeeCalculatorConfig}
import org.tessellation.schema.currencyMessage.fetchStakingBalance
import org.tessellation.schema.{GlobalSnapshotInfo, SnapshotOrdinal}

import eu.timepit.refined.types.numeric.{NonNegInt, NonNegLong}

trait SnapshotBinaryFeeCalculator[F[_]] {
  def calculateFee(event: StateChannelEvent, info: GlobalSnapshotInfo, ordinal: SnapshotOrdinal): F[NonNegLong]
}

object SnapshotBinaryFeeCalculator {
  def make[F[_]: MonadThrow](configs: SortedMap[SnapshotOrdinal, FeeCalculatorConfig]): SnapshotBinaryFeeCalculator[F] =
    make(FeeCalculator.make[F](configs))

  def make[F[_]: MonadThrow](feeCalculator: FeeCalculator[F]): SnapshotBinaryFeeCalculator[F] =
    (event: StateChannelEvent, info: GlobalSnapshotInfo, ordinal: SnapshotOrdinal) =>
      event.address
        .pure[F]
        .map(fetchStakingBalance(_, info))
        .flatMap { balance =>
          val binary = event.snapshotBinary.value
          val kbytes = NonNegInt.unsafeFrom(binary.content.length / 1024)
          feeCalculator
            .calculateRecommendedFee(Some(ordinal))(balance, kbytes, binary.fee.value)
            .map(_.value)
        }
}
