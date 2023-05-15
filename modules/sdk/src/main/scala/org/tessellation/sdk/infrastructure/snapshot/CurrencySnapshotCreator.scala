package org.tessellation.sdk.infrastructure.snapshot

import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.schema.{Block, CurrencyData, CurrencyDataInfo}
import org.tessellation.security.signature.Signed

trait CurrencySnapshotCreator[F[_]] {

  def createCurrencySnapshot(
    lastSnapshot: Signed[CurrencyIncrementalSnapshot],
    lastState: CurrencySnapshotInfo,
    blocks: Set[Signed[Block]],
    mintRewards: Boolean
  ): F[CurrencySnapshotCreationResult]

}

object CurrencySnapshotCreator {

  case class CurrencySnapshotCreationResult(
    snapshot: CurrencyIncrementalSnapshot,
    state: CurrencySnapshotInfo,
    awaitingBlocks: Set[Signed[Block]],
    rejectedBlocks: Set[Signed[Block]]
  )

}
