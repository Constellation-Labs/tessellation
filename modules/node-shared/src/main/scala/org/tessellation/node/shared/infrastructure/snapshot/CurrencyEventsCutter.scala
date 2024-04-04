package org.tessellation.node.shared.infrastructure.snapshot

import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._

import org.tessellation.currency.dataApplication.BaseDataApplicationL0Service
import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.json.JsonSerializer
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
  def make[F[_]: Async: JsonSerializer](maybeDataApplication: Option[BaseDataApplicationL0Service[F]]): CurrencyEventsCutter[F] = (
    ordinal: SnapshotOrdinal,
    acceptedBlocks: List[Signed[Block]],
    acceptedDataBlocks: List[Signed[DataApplicationBlock]]
  ) => {
    val blockEvents = acceptedBlocks.map(_.asLeft[Signed[DataApplicationBlock]])
    val dataBlockEvents = acceptedDataBlocks.map(_.asRight[Signed[Block]])
    val acceptedDataBlocksLength = acceptedDataBlocks.length
    val acceptedBlocksLength = acceptedBlocks.length

    val eventsToCutFrom: F[Option[List[CurrencySnapshotEvent]]] =
      maybeDataApplication match {
        case None => if (acceptedBlocksLength <= 1) none.pure[F] else blockEvents.some.pure[F]
        case Some(dataApplication) =>
          if (acceptedBlocksLength + acceptedDataBlocksLength <= 1) {
            none.pure[F]
          } else if (acceptedBlocksLength < acceptedDataBlocksLength) {
            dataBlockEvents.some.pure[F]
          } else if (acceptedBlocksLength > acceptedDataBlocksLength) {
            blockEvents.some.pure[F]
          } else {
            implicit val dataEncoder = dataApplication.dataEncoder

            val lastBlockSize: F[Option[Int]] = acceptedBlocks.lastOption.traverse(a => JsonSerializer[F].serialize(a).map(_.length))
            val lastDataBlockSize: F[Option[Int]] =
              acceptedDataBlocks.lastOption.traverse(JsonSerializer[F].serialize(_)).map(_.map(_.length))

            (lastBlockSize, lastDataBlockSize).tupled.flatMap {
              case (Some(lbs), Some(ldbs)) =>
                if (lbs > ldbs) {
                  blockEvents.some.pure[F]
                } else if (lbs < ldbs) {
                  dataBlockEvents.some.pure[F]
                } else {
                  Random
                    .scalaUtilRandomSeedLong(ordinal.value)
                    .flatMap(_.elementOf(Set(blockEvents.some, dataBlockEvents.some)))
                }
              case (Some(_), None) => blockEvents.some.pure[F]
              case (None, Some(_)) => dataBlockEvents.some.pure[F]
              case _               => none.pure[F]
            }
          }
      }

    eventsToCutFrom.map(_.collect {
      case remainingEvents :+ excessiveEvent =>
        (remainingEvents.toSet, excessiveEvent)
    })
  }
}
