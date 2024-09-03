package io.constellationnetwork.node.shared.domain.block.processing

import io.constellationnetwork.schema.{Block, SnapshotOrdinal}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

trait BlockAcceptanceManager[F[_]] {

  def acceptBlocksIteratively(
    blocks: List[Signed[Block]],
    context: BlockAcceptanceContext[F],
    snapshotOrdinal: SnapshotOrdinal
  )(implicit hasher: Hasher[F]): F[BlockAcceptanceResult]

  def acceptBlock(
    block: Signed[Block],
    context: BlockAcceptanceContext[F],
    snapshotOrdinal: SnapshotOrdinal
  )(implicit hasher: Hasher[F]): F[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]]

}
