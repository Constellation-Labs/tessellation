package org.tessellation.l0.infrastructure.snapshot

import cats.data.NonEmptySet
import cats.effect.kernel.Async
import cats.syntax.functor._

import scala.util.control.NoStackTrace
import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.kernel.StateChannelSnapshot
import org.tessellation.l0.domain.snapshot.{GlobalSnapshot, GlobalSnapshotOrdinal, SnapshotService, SnapshotStorage}
import org.tessellation.schema.height.Height
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.Hashed
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import io.estatico.newtype.ops._
import org.tessellation.security.signature.Signed

object SnapshotService {

  private val heightInterval = PosLong(2L)

  def make[F[_]: Async](
    snapshotStorage: SnapshotStorage[F]
  ): SnapshotService[F] =
    new SnapshotService[F] {

      def createEmptySnapshot(
        prev: GlobalSnapshot,
        snapshots: Set[StateChannelSnapshot],
        nextFacilitators: NonEmptySet[PeerId]
      ): GlobalSnapshot =
        GlobalSnapshot(
          ordinal = GlobalSnapshotOrdinal(prev.ordinal.coerce + NonNegLong(1)),
          height = prev.height,
          subHeight = Height(prev.subHeight.coerce + NonNegLong(1)),
          blocks = Set.empty[Signed[DAGBlock]],
          snapshots = snapshots,
          nextFacilitators = nextFacilitators
        )

      def createSnapshot(
        blocks: Set[Hashed[DAGBlock]],
        snapshots: Set[StateChannelSnapshot],
        nextFacilitators: NonEmptySet[PeerId]
      ): F[GlobalSnapshot] =
        for {
          previousSnapshotHeight <- snapshotStorage.getLastSnapshotHeight
          nextSnapshotHeight = Height(previousSnapshotHeight.coerce + heightInterval)

          blocksForNextSnapshot = blocks.filter { block =>
            val blockHeight = block.signed.height.coerce
            blockHeight > previousSnapshotHeight.coerce && blockHeight <= nextSnapshotHeight.coerce
          }.map(_.signed)

          snapshot = GlobalSnapshot(blocksForNextSnapshot, snapshots, nextFacilitators)
        } yield snapshot
    }

  sealed trait SnapshotCreationError extends NoStackTrace {
    val errorMessage: String
    override def getMessage: String = errorMessage
  }

}
