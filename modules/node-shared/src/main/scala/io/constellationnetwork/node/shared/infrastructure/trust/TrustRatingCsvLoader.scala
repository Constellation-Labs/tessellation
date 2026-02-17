package io.constellationnetwork.node.shared.infrastructure.trust

import java.nio.file.NoSuchFileException

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.domain.trust.csv._
import io.constellationnetwork.schema.trust.{PeerObservationAdjustmentUpdate, PeerObservationAdjustmentUpdateBatch}

import fs2.data.csv._
import fs2.io.file.{Files, Path}
import fs2.text
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait TrustRatingCsvLoader[F[_]] {
  def load(path: Path): F[PeerObservationAdjustmentUpdateBatch]
}

object TrustRatingCsvLoader {

  def make[F[_]: Async]: TrustRatingCsvLoader[F] =
    (path: Path) => {
      val logger = Slf4jLogger.getLoggerFromName[F]("TrustRatingCsvLoader")
      Files
        .forAsync[F]
        .readAll(path)
        .through(text.utf8.decode)
        .through(
          decodeWithoutHeaders[PeerObservationAdjustmentUpdate]()
        )
        .compile
        .toList
        .map(PeerObservationAdjustmentUpdateBatch(_))
        .handleErrorWith {
          case _: NoSuchFileException =>
            logger.warn(s"Trust rating file not found at $path, using empty batch") >>
              PeerObservationAdjustmentUpdateBatch(List.empty).pure[F]
          case e => Async[F].raiseError(e)
        }
    }
}
