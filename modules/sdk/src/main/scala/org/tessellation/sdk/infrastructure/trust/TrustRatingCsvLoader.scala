package org.tessellation.sdk.infrastructure.trust

import cats.effect.Async
import cats.syntax.functor._

import org.tessellation.schema.trust.{PeerObservationAdjustmentUpdate, PeerObservationAdjustmentUpdateBatch}
import org.tessellation.sdk.domain.trust._

import fs2.data.csv._
import fs2.io.file.{Files, Path}
import fs2.text

trait TrustRatingCsvLoader[F[_]] {
  def load(path: Path): F[PeerObservationAdjustmentUpdateBatch]
}

object TrustRatingCsvLoader {

  def make[F[_]: Async]: TrustRatingCsvLoader[F] =
    (path: Path) =>
      Files[F]
        .readAll(path)
        .through(text.utf8.decode)
        .through(
          decodeWithoutHeaders[PeerObservationAdjustmentUpdate]()
        )
        .compile
        .toList
        .map(PeerObservationAdjustmentUpdateBatch(_))
}
