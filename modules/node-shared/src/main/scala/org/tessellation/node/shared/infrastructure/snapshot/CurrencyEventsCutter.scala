package org.tessellation.node.shared.infrastructure.snapshot

import cats.data.OptionT
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._

import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.snapshot.currency.CurrencySnapshotEvent
import org.tessellation.schema.{Block, SnapshotOrdinal}
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._

trait CurrencyEventsCutter[F[_]] {
  def cut(
    ordinal: SnapshotOrdinal,
    acceptedBlocks: List[Signed[Block]],
    acceptedDataBlocks: List[Signed[DataApplicationBlock]]
  ): F[Option[(Set[CurrencySnapshotEvent], CurrencySnapshotEvent)]]
}

object CurrencyEventsCutter {
  def make[F[_]: Async: KryoSerializer]: CurrencyEventsCutter[F] = (
    ordinal: SnapshotOrdinal,
    acceptedBlocks: List[Signed[Block]],
    acceptedDataBlocks: List[Signed[DataApplicationBlock]]
  ) => {
    val blockEvents = OptionT.some(acceptedBlocks.map(_.asLeft[Signed[DataApplicationBlock]]))
    val dataBlockEvents = OptionT.some(acceptedDataBlocks.map(_.asRight[Signed[Block]]))
    val acceptedDataBlocksLength = acceptedDataBlocks.length
    val acceptedBlocksLength = acceptedBlocks.length

    val eventsToCutFrom =
      if (acceptedBlocksLength + acceptedDataBlocksLength <= 1) {
        OptionT.none[F, List[CurrencySnapshotEvent]]
      } else if (acceptedBlocksLength < acceptedDataBlocksLength) {
        dataBlockEvents
      } else if (acceptedBlocksLength > acceptedDataBlocksLength) {
        blockEvents
      } else {
        val lastBlockSize: OptionT[F, Int] = OptionT
          .fromOption(acceptedBlocks.lastOption)
          .semiflatMap(_.toBinaryF.map(_.length))
        val lastDataBlockSize: OptionT[F, Int] = OptionT
          .fromOption(acceptedDataBlocks.lastOption)
          .semiflatMap(_.toBinaryF.map(_.length))

        (lastBlockSize, lastDataBlockSize).flatMapN {
          case (lbs, ldbs) =>
            if (lbs > ldbs) {
              blockEvents
            } else if (lbs < ldbs) {
              dataBlockEvents
            } else {
              OptionT
                .liftF(Random.scalaUtilRandomSeedLong(ordinal.value))
                .semiflatMap(_.elementOf(Set(blockEvents, dataBlockEvents)))
                .flatten
            }
        }
      }

    eventsToCutFrom.collect {
      case remainingEvents :+ excessiveEvent =>
        (remainingEvents.toSet, excessiveEvent)
    }.value
  }
}
