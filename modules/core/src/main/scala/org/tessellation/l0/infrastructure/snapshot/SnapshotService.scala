package org.tessellation.l0.infrastructure.snapshot

import cats.data.NonEmptySet
import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.semigroup._

import scala.util.control.NoStackTrace

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.ext.fs2.switchRepeat
import org.tessellation.l0.config.SnapshotConfig
import org.tessellation.l0.domain.snapshot._
import org.tessellation.l0.schema.snapshot.{GlobalSnapshot, GlobalSnapshotOrdinal}
import org.tessellation.schema.height.Height
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.signature.Signed

import eu.timepit.refined.cats.nonNegLongCommutativeMonoid
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.Stream
import io.estatico.newtype.ops._

object SnapshotService {

  def make[F[_]: Async](
    snapshotStorage: SnapshotStorage[F],
    blocksQueue: Queue[F, Set[Signed[DAGBlock]]],
    config: SnapshotConfig
  ): SnapshotService[F] =
    new SnapshotService[F] {

      val snapshotCreation: Stream[F, GlobalSnapshot] =
        Stream
          .fromQueueUnterminated(blocksQueue)
          .evalMap(createSnapshot)
          .through(switchRepeat(config.fallbackTriggerTimeout, Stream.eval(createSnapshot())))

      def createSnapshot(): F[GlobalSnapshot] =
        for {
          prevHeight <- snapshotStorage.getLastSnapshotHeight
          prevSubHeight <- snapshotStorage.getLastSnapshotSubHeight
          prevOrdinal <- snapshotStorage.getLastSnapshotOrdinal
          nextFacilitators <- selectNextFacilitators
          snapshots <- snapshotStorage.getStateChannelSnapshots
        } yield
          GlobalSnapshot(
            ordinal = GlobalSnapshotOrdinal(prevOrdinal.value |+| NonNegLong(1L)),
            height = prevHeight,
            subHeight = Height(prevSubHeight.coerce[Long] + 1L),
            blocks = Set.empty[Signed[DAGBlock]],
            snapshots = snapshots,
            nextFacilitators = nextFacilitators
          )

      def createSnapshot(
        blocks: Set[Signed[DAGBlock]]
      ): F[GlobalSnapshot] =
        for {
          prevHeight <- snapshotStorage.getLastSnapshotHeight
          prevOrdinal <- snapshotStorage.getLastSnapshotOrdinal
          nextFacilitators <- selectNextFacilitators
          snapshots <- snapshotStorage.getStateChannelSnapshots
        } yield
          GlobalSnapshot(
            ordinal = GlobalSnapshotOrdinal(prevOrdinal.value |+| NonNegLong(1L)),
            height = Height(prevHeight.coerce + 1L),
            subHeight = Height(0L),
            blocks = blocks,
            snapshots = snapshots,
            nextFacilitators = nextFacilitators
          )

      def selectNextFacilitators: F[NonEmptySet[PeerId]] = ???
    }

  sealed trait SnapshotCreationError extends NoStackTrace {
    val errorMessage: String
    override def getMessage: String = errorMessage
  }

}
