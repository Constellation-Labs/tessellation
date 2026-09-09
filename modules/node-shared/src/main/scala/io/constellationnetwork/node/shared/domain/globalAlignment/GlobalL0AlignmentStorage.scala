package io.constellationnetwork.node.shared.domain.globalAlignment

import cats.effect.Async
import cats.effect.kernel.Ref
import cats.syntax.all._

trait GlobalL0AlignmentStorage[F[_]] {
  def updateShouldRedownload(value: Boolean, reasons: List[String]): F[Unit]

  /** Atomically read the current flag and reset it to empty in one operation.
    *
    * Replaces the racy `getShouldRedownload` ... later `clean()` pair: with two separate ops a concurrent `updateShouldRedownload(true)`
    * landing between the read and the clear was silently wiped by the unconditional `set(empty)`. `getAndSet` consumes exactly the value
    * the caller acts on; any flag raised afterwards survives for the next cycle.
    */
  def consumeShouldRedownload: F[ShouldRedownload]
}

object GlobalL0AlignmentStorage {
  def make[F[_]: Async]: F[GlobalL0AlignmentStorage[F]] =
    Ref.of[F, ShouldRedownload](ShouldRedownload.empty).map(make(_))

  def make[F[_]: Async](shouldRedownload: Ref[F, ShouldRedownload]): GlobalL0AlignmentStorage[F] =
    new GlobalL0AlignmentStorage[F] {
      def consumeShouldRedownload: F[ShouldRedownload] =
        shouldRedownload.getAndSet(ShouldRedownload.empty)

      def updateShouldRedownload(value: Boolean, reasons: List[String]): F[Unit] =
        shouldRedownload.update { current =>
          if (current.value) {
            current.copy(reason = current.reason ++ reasons)
          } else {
            ShouldRedownload(value, reasons)
          }
        }
    }
}
