package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.kernel.Async
import cats.syntax.all._

import io.constellationnetwork.ext.crypto.RefinedHasher
import io.constellationnetwork.node.shared.domain.block.processing.BlockRejectionReason
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash

import eu.timepit.refined.types.all.PosInt

object CurrencySnapshotEventValidationErrorStorage {
  def make[F[_]: Async: Hasher](maxSize: PosInt): F[ValidationErrorStorage[F, CurrencySnapshotEvent, BlockRejectionReason]] =
    ValidationErrorStorage.make(maxSize, hashes[F])

  private def hashes[F[_]: Async: Hasher](event: CurrencySnapshotEvent): F[List[Hash]] =
    event match {
      case BlockEvent(block) =>
        block.value.transactions.toList.traverse(_.value.hash)
      case DataApplicationBlockEvent(dataBlock) => dataBlock.value.dataTransactionsHashes.flatten.toList.pure[F]
      case CurrencyMessageEvent(message)        => message.value.hash.map(List(_))
      case AllowSpendBlockEvent(allowSpend)     => allowSpend.hash.map(List(_))
      case GlobalSnapshotSyncEvent(sync)        => sync.hash.map(List(_))
      case TokenLockBlockEvent(tokenLock)       => tokenLock.hash.map(List(_))
    }
}
