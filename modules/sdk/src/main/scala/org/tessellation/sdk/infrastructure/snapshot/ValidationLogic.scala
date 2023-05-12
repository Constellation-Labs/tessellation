package org.tessellation.sdk.infrastructure.snapshot

import org.tessellation.schema.{Block, BlockAsActiveTip, CommonSnapshot, CommonSnapshotInfo}
import org.tessellation.sdk.domain.block.processing.BlockAcceptanceManager
import org.tessellation.security.signature.Signed

trait SnapshotCreator[F[_]] {

  def createSnapshot(
    blocks: Set[Block],
    lastSnapshot: Signed[CommonSnapshot],
    lastContext: CommonSnapshotInfo
  ): F[(CommonSnapshot, CommonSnapshotInfo, Set[BlockAsActiveTip])]

}

object SnapshotCreator {


  def make[F](blockAcceptanceManager: BlockAcceptanceManager[F],)


}
