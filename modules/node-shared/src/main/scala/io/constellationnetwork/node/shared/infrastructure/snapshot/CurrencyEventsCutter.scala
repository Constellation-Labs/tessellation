package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.BaseDataApplicationL0Service
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationBlock
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.currencyMessage.CurrencyMessage
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.schema.{Block, SnapshotOrdinal}
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._

trait CurrencyEventsCutter[F[_]] {
  def cut(
    ordinal: SnapshotOrdinal,
    acceptedBlocks: List[Signed[Block]],
    acceptedTokenLockBlocks: List[Signed[TokenLockBlock]],
    acceptedDataBlocks: List[Signed[DataApplicationBlock]],
    acceptedMessages: List[Signed[CurrencyMessage]]
  ): F[Option[(Set[CurrencySnapshotEvent], CurrencySnapshotEvent)]]
}

object CurrencyEventsCutter {
  def make[F[_]: Async: JsonSerializer](maybeDataApplication: Option[BaseDataApplicationL0Service[F]]): CurrencyEventsCutter[F] = (
    ordinal: SnapshotOrdinal,
    acceptedBlocks: List[Signed[Block]],
    acceptedTokenLockBlocks: List[Signed[TokenLockBlock]],
    acceptedDataBlocks: List[Signed[DataApplicationBlock]],
    acceptedMessages: List[Signed[CurrencyMessage]]
  ) => {
    val blockEvents = acceptedBlocks.map(BlockEvent(_))
    val tokenLockBlockEvents = acceptedTokenLockBlocks.map(TokenLockBlockEvent(_))
    val dataBlockEvents = acceptedDataBlocks.map(DataApplicationBlockEvent(_))
    val currencyMessageEvents = acceptedMessages.map(CurrencyMessageEvent(_))
    val acceptedDataBlocksLength = acceptedDataBlocks.length
    val acceptedBlocksLength = acceptedBlocks.length
    val acceptedTokenLockBlocksLength = acceptedTokenLockBlocks.length

    val eventsToCutFrom: F[Option[List[CurrencySnapshotEvent]]] =
      maybeDataApplication match {
        case None =>
          if (acceptedBlocksLength <= 1 && acceptedTokenLockBlocksLength <= 1) {
            none.pure[F]
          } else {
            (blockEvents.widen[CurrencySnapshotEvent] ++ tokenLockBlockEvents.widen[CurrencySnapshotEvent]).some.pure[F]
          }
        case Some(dataApplication) =>
          if (acceptedBlocksLength + acceptedDataBlocksLength + acceptedTokenLockBlocksLength <= 1) {
            none.pure[F]
          } else if (acceptedBlocksLength < acceptedDataBlocksLength && acceptedTokenLockBlocksLength < acceptedDataBlocksLength) {
            dataBlockEvents.widen[CurrencySnapshotEvent].some.pure[F]
          } else if (acceptedBlocksLength > acceptedDataBlocksLength && acceptedBlocksLength > acceptedTokenLockBlocksLength) {
            blockEvents.widen[CurrencySnapshotEvent].some.pure[F]
          } else if (acceptedTokenLockBlocksLength > acceptedDataBlocksLength && acceptedTokenLockBlocksLength > acceptedBlocksLength) {
            tokenLockBlockEvents.widen[CurrencySnapshotEvent].some.pure[F]
          } else {
            implicit val dataEncoder = dataApplication.dataEncoder

            val lastBlockSize: F[Option[Int]] = acceptedBlocks.lastOption.traverse(a => JsonSerializer[F].serialize(a).map(_.length))
            val lastTokenLockBlockSize: F[Option[Int]] =
              acceptedTokenLockBlocks.lastOption.traverse(a => JsonSerializer[F].serialize(a).map(_.length))
            val lastDataBlockSize: F[Option[Int]] =
              acceptedDataBlocks.lastOption.traverse(JsonSerializer[F].serialize(_)).map(_.map(_.length))

            (lastBlockSize, lastTokenLockBlockSize, lastDataBlockSize).tupled.flatMap {
              case (Some(lbs), Some(ltlbs), Some(ldbs)) =>
                if (lbs > ldbs && lbs > ltlbs) {
                  blockEvents.widen[CurrencySnapshotEvent].some.pure[F]
                } else if (lbs < ldbs && ltlbs < ldbs) {
                  dataBlockEvents.widen[CurrencySnapshotEvent].some.pure[F]
                } else if (ltlbs > lbs && ltlbs > ldbs) {
                  tokenLockBlockEvents.widen[CurrencySnapshotEvent].some.pure[F]
                } else {
                  Random
                    .scalaUtilRandomSeedLong(ordinal.value)
                    .flatMap(_.elementOf(Set(blockEvents.some, tokenLockBlockEvents.some, dataBlockEvents.some)))
                }
              case (Some(_), None, None) => blockEvents.widen[CurrencySnapshotEvent].some.pure[F]
              case (None, Some(_), None) => tokenLockBlockEvents.widen[CurrencySnapshotEvent].some.pure[F]
              case (None, None, Some(_)) => dataBlockEvents.widen[CurrencySnapshotEvent].some.pure[F]
              case _                     => none.pure[F]
            }
          }
      }

    eventsToCutFrom.map(_.collect {
      case remainingEvents :+ excessiveEvent =>
        (remainingEvents.toSet ++ currencyMessageEvents.toSet, excessiveEvent)
    })
  }
}
