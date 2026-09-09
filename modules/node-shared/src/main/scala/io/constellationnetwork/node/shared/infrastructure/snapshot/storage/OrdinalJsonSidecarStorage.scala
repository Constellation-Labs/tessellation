package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.cutoff.LogarithmicOrdinalCutoff
import io.constellationnetwork.ext.cats.syntax.partialPrevious.catsSyntaxPartialPrevious
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.storage.CrashSafeAtomicFileWriter
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.storage.SerializableLocalFileSystemStorage

import fs2.io.file.Path
import io.circe.{Decoder, Encoder}

/** Generic, node-local storage for typed recovery evidence indexed by snapshot ordinal.
  *
  * Values use the repository's ordinary `JsonSerializer`; this class deliberately defines no alternate JSON printer, canonicalization pass,
  * or hashing scheme. A dedicated directory keeps filenames numeric, which lets rollback remove every sidecar above a checkpoint and lets
  * the existing logarithmic snapshot-info cutoff retain matching recovery evidence without knowing the value type. Writes and deletes use
  * the same crash-safe atomic byte-file primitive as the certified vote-lock journal. A successful write is therefore a valid durability
  * boundary before consensus deletes the matching pre-finalization safety lock, and acknowledged rollback/retention cleanup cannot
  * resurrect stale sidecars after a crash.
  *
  * Missing and malformed files read as `None`. A corrupt sidecar is an availability loss, never evidence: callers must still
  * cryptographically validate every value before adoption.
  */
trait OrdinalJsonSidecarStorage[F[_], A] {
  def write(ordinal: SnapshotOrdinal, value: A): F[Unit]
  def read(ordinal: SnapshotOrdinal): F[Option[A]]
  def delete(ordinal: SnapshotOrdinal): F[Unit]
  def deleteAbove(ordinal: SnapshotOrdinal): F[Unit]
  def retain(
    cutoffOrdinal: SnapshotOrdinal,
    currentOrdinal: SnapshotOrdinal,
    pinnedOrdinals: Set[SnapshotOrdinal] = Set.empty
  ): F[Unit]
}

object OrdinalJsonSidecarStorage {

  def make[F[_]: Async: JsonSerializer, A: Encoder: Decoder](base: Path): F[OrdinalJsonSidecarStorage[F, A]] =
    CrashSafeAtomicFileWriter.make[F](base).map(new Impl[F, A](base, _))

  private final class Impl[F[_]: Async: JsonSerializer, A: Encoder: Decoder](
    base: Path,
    atomicFileWriter: CrashSafeAtomicFileWriter[F]
  ) extends SerializableLocalFileSystemStorage[F, A](base)
      with OrdinalJsonSidecarStorage[F, A] {

    private def fileName(ordinal: SnapshotOrdinal): String = ordinal.value.value.toString

    def deserializeFallback(bytes: Array[Byte]): Either[Throwable, A] =
      new IllegalArgumentException("No legacy decoder exists for typed ordinal sidecars").asLeft[A]

    def write(ordinal: SnapshotOrdinal, value: A): F[Unit] =
      JsonSerializer[F].serialize(value).flatMap(atomicFileWriter.write(fileName(ordinal), _))

    def read(ordinal: SnapshotOrdinal): F[Option[A]] =
      super.read(fileName(ordinal)).handleErrorWith { error =>
        logger.warn(error)(s"Failed to read typed consensus sidecar at ordinal=${ordinal.value.value}; treating as missing") >>
          none[A].pure[F]
      }

    def delete(ordinal: SnapshotOrdinal): F[Unit] =
      atomicFileWriter.delete(fileName(ordinal)).void

    def deleteAbove(ordinal: SnapshotOrdinal): F[Unit] =
      listFiles.flatMap(
        _.evalMap { file =>
          file.name.toLongOption
            .filter(_ > ordinal.value.value)
            .fold(Async[F].unit)(value => delete(SnapshotOrdinal.unsafeApply(value)))
        }.compile.drain
      ) >> atomicFileWriter.syncDirectory

    /** Apply the repository's ordinary snapshot-info retention policy.
      *
      * Recovery evidence retains the same logarithmic ordinal set as the state context it authenticates. This avoids an independent
      * retention knob and prevents complete typed outcomes from accumulating after their corresponding snapshot info has been pruned.
      */
    def retain(
      cutoffOrdinal: SnapshotOrdinal,
      currentOrdinal: SnapshotOrdinal,
      pinnedOrdinals: Set[SnapshotOrdinal]
    ): F[Unit] = {
      // Certified outcome N is the authority needed to validate/restore N+1.  The
      // generic logarithmic cutoff is allowed to omit N-1, so retain that exact
      // predecessor explicitly.  This is deliberately a storage invariant rather
      // than a caller convention: every typed consensus sidecar keeps the minimum
      // contiguous recovery window.
      val immediateRecoveryWindow =
        currentOrdinal.partialPrevious.fold(Set(currentOrdinal))(previous => Set(previous, currentOrdinal))
      val toKeep = LogarithmicOrdinalCutoff.make.cutoff(cutoffOrdinal, currentOrdinal) ++ immediateRecoveryWindow ++ pinnedOrdinals

      listFiles.flatMap(
        _.evalMap { file =>
          file.name.toLongOption
            .flatMap(SnapshotOrdinal(_))
            .filterNot(toKeep.contains)
            .fold(Async[F].unit)(delete)
        }.compile.drain
      ) >> atomicFileWriter.syncDirectory
    }
  }
}
