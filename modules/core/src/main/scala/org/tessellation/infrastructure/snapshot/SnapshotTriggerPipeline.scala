package org.tessellation.infrastructure.snapshot

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.effect.std.Queue
import cats.kernel.Next
import cats.syntax.either._
import cats.syntax.functor._

import scala.util.control.NoStackTrace

import org.tessellation.config.types.SnapshotConfig
import org.tessellation.dag.domain.block.{DAGBlock, L1Output}
import org.tessellation.domain.snapshot._
import org.tessellation.ext.fs2.switchRepeat
import org.tessellation.schema.height.Height
import org.tessellation.security.signature.Signed

import fs2.Stream

object SnapshotTriggerPipeline {

  def stream[F[_]: Async](
    globalSnapshotStorage: GlobalSnapshotStorage[F],
    snapshotPreconditionsValidator: SnapshotPreconditionsValidator[F],
    l1OutputQueue: Queue[F, Signed[L1Output]],
    config: SnapshotConfig
  )(implicit N: Next[Height]): Stream[F, Either[Signed[DAGBlock], SnapshotTrigger]] = {

    def getLastSnapshotHeight: F[Option[Height]] = globalSnapshotStorage.head.map(_.map(_.height))

    Stream
      .fromQueueUnterminated(l1OutputQueue)
      .zip(Stream.eval(getLastSnapshotHeight).flatMap(_.map(Stream.emit).getOrElse(Stream.empty)))
      .flatMap {
        case (data, lastSnapshotHeight) =>
          snapshotPreconditionsValidator.validate(lastSnapshotHeight, data) match {
            case Valid(lastSnapshotHeight) =>
              Stream(
                data.block.asLeft[SnapshotTrigger],
                TipSnapshotTrigger(lastSnapshotHeight).asRight[Signed[DAGBlock]]
              )
            case Invalid(_) => Stream(data.block.asLeft[SnapshotTrigger])
          }
      }
      .through(
        switchRepeat(
          config.fallbackTriggerTimeout,
          Stream(TimeSnapshotTrigger().asRight[Signed[DAGBlock]])
        )
      )

  }

  sealed trait SnapshotCreationError extends NoStackTrace {
    val errorMessage: String
    override def getMessage: String = errorMessage
  }

}
