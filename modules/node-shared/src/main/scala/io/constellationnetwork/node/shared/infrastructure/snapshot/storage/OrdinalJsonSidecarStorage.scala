package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.cutoff.LogarithmicOrdinalCutoff
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.storage.SerializableLocalFileSystemStorage

import fs2.io.file.Path
import io.circe.{Decoder, Encoder}

/** Generic, node-local storage for typed recovery evidence indexed by snapshot ordinal.
  *
  * Values use the repository's ordinary `JsonSerializer`; this class deliberately defines no
  * alternate JSON printer, canonicalization pass, or hashing scheme. A dedicated directory keeps
  * filenames numeric, which lets rollback remove every sidecar above a checkpoint and lets the
  * existing logarithmic snapshot-info cutoff retain matching recovery evidence without knowing
  * the value type.
  *
  * Missing and malformed files read as `None`. A corrupt sidecar is an availability loss, never
  * evidence: callers must still cryptographically validate every value before adoption.
  */
trait OrdinalJsonSidecarStorage[F[_], A] {
  def write(ordinal: SnapshotOrdinal, value: A): F[Unit]
  def read(ordinal: SnapshotOrdinal): F[Option[A]]
  def delete(ordinal: SnapshotOrdinal): F[Unit]
  def deleteAbove(ordinal: SnapshotOrdinal): F[Unit]
  def retain(cutoffOrdinal: SnapshotOrdinal, currentOrdinal: SnapshotOrdinal): F[Unit]
}

object OrdinalJsonSidecarStorage {

  def make[F[_]: Async: JsonSerializer, A: Encoder: Decoder](base: Path): F[OrdinalJsonSidecarStorage[F, A]] = {
    val storage = new Impl[F, A](base)
    storage.createDirectoryIfNotExists().rethrowT.as(storage)
  }

  private final class Impl[F[_]: Async: JsonSerializer, A: Encoder: Decoder](base: Path)
      extends SerializableLocalFileSystemStorage[F, A](base)
      with OrdinalJsonSidecarStorage[F, A] {

    private def fileName(ordinal: SnapshotOrdinal): String = ordinal.value.value.toString

    def deserializeFallback(bytes: Array[Byte]): Either[Throwable, A] =
      new IllegalArgumentException("No legacy decoder exists for typed ordinal sidecars").asLeft[A]

    def write(ordinal: SnapshotOrdinal, value: A): F[Unit] =
      super.write(fileName(ordinal), value)

    def read(ordinal: SnapshotOrdinal): F[Option[A]] =
      super.read(fileName(ordinal)).handleErrorWith { error =>
        logger.warn(error)(s"Failed to read typed consensus sidecar at ordinal=${ordinal.value.value}; treating as missing") >>
          none[A].pure[F]
      }

    def delete(ordinal: SnapshotOrdinal): F[Unit] =
      super.delete(fileName(ordinal))

    def deleteAbove(ordinal: SnapshotOrdinal): F[Unit] =
      listFiles.flatMap(
        _.evalMap { file =>
          file.name.toLongOption
            .filter(_ > ordinal.value.value)
            .fold(Async[F].unit)(value => delete(SnapshotOrdinal.unsafeApply(value)))
        }.compile.drain
      )

    /** Apply the repository's ordinary snapshot-info retention policy.
      *
      * Recovery evidence retains the same logarithmic ordinal set as the state context it authenticates. This avoids an independent
      * retention knob and prevents complete typed outcomes from accumulating after their corresponding snapshot info has been pruned.
      */
    def retain(cutoffOrdinal: SnapshotOrdinal, currentOrdinal: SnapshotOrdinal): F[Unit] = {
      val toKeep = LogarithmicOrdinalCutoff.make.cutoff(cutoffOrdinal, currentOrdinal)

      listFiles.flatMap(
        _.evalMap { file =>
          file.name.toLongOption
            .flatMap(SnapshotOrdinal(_))
            .filterNot(toKeep.contains)
            .fold(Async[F].unit)(delete)
        }.compile.drain
      )
    }
  }
}
