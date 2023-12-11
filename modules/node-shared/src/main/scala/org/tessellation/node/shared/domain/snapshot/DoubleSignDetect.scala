package org.tessellation.node.shared.domain.snapshot

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.all._

import org.tessellation.node.shared.config.types.{DoubleSignDetectConfig, DoubleSignDetectDaemonConfig}
import org.tessellation.node.shared.domain.Daemon
import org.tessellation.node.shared.domain.fork.{ForkInfo, ForkInfoEntries, ForkInfoStorage}
import org.tessellation.node.shared.domain.snapshot.DoubleSignDetect.IndexedForkInfo
import org.tessellation.schema.peer.PeerId

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object DoubleSignDetect {
  case class IndexedForkInfo(info: ForkInfo, idx: NonNegLong)

  def daemon[F[_]: Async: Supervisor](
    doubleSignDetect: DoubleSignDetect[F],
    config: DoubleSignDetectDaemonConfig
  ): Daemon[F] =
    Daemon.periodic(doubleSignDetect.run, config.runInterval)
}

class DoubleSignDetect[F[_]: Async](
  storage: ForkInfoStorage[F],
  config: DoubleSignDetectConfig
) {
  private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger

  private def isDoubleSign(previous: IndexedForkInfo, current: IndexedForkInfo): Boolean =
    (current.idx - previous.idx <= config.maxDetectionDelta) && (previous.info.hash =!= current.info.hash)

  private def hasDoubleSign(info: Iterable[IndexedForkInfo]): Boolean =
    info.toList
      .sortBy(_.idx.value)
      .tailRecM[Option, Boolean] {
        case Nil | _ :: Nil                                => false.asRight.some
        case prev :: curr :: _ if isDoubleSign(prev, curr) => true.asRight.some
        case _ :: tail                                     => tail.asLeft.some
      }
      .contains(true)

  def validate(peerId: PeerId, entries: ForkInfoEntries): Option[DetectedDoubleSign] = {
    val grouped: Iterable[Iterable[IndexedForkInfo]] =
      entries.getEntries.zipWithIndex.map {
        case (info, idx) =>
          val refinedIdx = Refined.unsafeApply[Long, NonNegative](idx.toLong)

          IndexedForkInfo(info, refinedIdx)
      }
        .groupBy(_.info.ordinal)
        .values

    grouped.find(hasDoubleSign).map { found =>
      val deltaSum = found.lazyZip(found.tail).map { case (x, y) => y.idx - x.idx }.sum

      DetectedDoubleSign(
        peerId = peerId,
        ordinal = found.head.info.ordinal,
        delta = Refined.unsafeApply[Long, NonNegative](deltaSum),
        hashes = found.map(_.info.hash).toSeq
      )
    }
  }

  def run: F[Unit] = storage.getForkInfo.flatMap {
    _.forks.toList.traverse_ {
      case (id, entries) =>
        validate(id, entries).traverse_ { detection =>
          logger.warn(s"Found a double signing: ${detection.show}")
        }
    }
  }

}
