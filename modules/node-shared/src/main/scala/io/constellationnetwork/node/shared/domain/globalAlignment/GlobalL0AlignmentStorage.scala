package io.constellationnetwork.node.shared.domain.globalAlignment

import cats.effect.Async
import cats.effect.kernel.Ref
import cats.syntax.all._

trait GlobalL0AlignmentStorage[F[_]] {
  def getShouldRedownload: F[ShouldRedownload]

  def updateShouldRedownload(value: Boolean, reasons: List[String]): F[Unit]

  def clean(): F[Unit]
}

object GlobalL0AlignmentStorage {
  def make[F[_]: Async]: F[GlobalL0AlignmentStorage[F]] =
    Ref.of[F, ShouldRedownload](ShouldRedownload.empty).map(make(_))

  def make[F[_]: Async](shouldRedownload: Ref[F, ShouldRedownload]): GlobalL0AlignmentStorage[F] =
    new GlobalL0AlignmentStorage[F] {
      def getShouldRedownload: F[ShouldRedownload] =
        shouldRedownload.get

      def updateShouldRedownload(value: Boolean, reasons: List[String]): F[Unit] =
        shouldRedownload.update { current =>
          if (current.value) {
            current.copy(reason = current.reason ++ reasons)
          } else {
            ShouldRedownload(value, reasons)
          }
        }

      def clean(): F[Unit] =
        shouldRedownload.set(ShouldRedownload.empty)
    }
}
