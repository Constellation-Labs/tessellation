package io.constellationnetwork.node.shared.infrastructure.consensus

import java.nio.file.{Files => JFiles}

import cats.Applicative
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.storage.CrashSafeAtomicFileWriter
import io.constellationnetwork.schema.SnapshotOrdinal

import fs2.io.file.Path
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

/** Durable, node-local safety storage for a certified consensus vote lock.
  *
  * The interface is key-generic so the consensus engine is not coupled to either L0 layer. DAG L0 and Currency L0 both use the
  * SnapshotOrdinal filesystem implementation below. This is not consensus state and is never hashed, signed, or sent on the wire.
  */
trait CertifiedVoteLockPersistence[F[_], Key] {
  def read(key: Key): F[Option[CertifiedVoteLock]]
  def write(key: Key, lock: CertifiedVoteLock): F[Unit]
  def delete(key: Key): F[Unit]
  def deleteAtOrBelow(key: Key): F[Unit]
  def deleteAbove(key: Key): F[Unit]
}

object CertifiedVoteLockPersistence {

  val Protocol: String = "certified-vote-lock-v1"
  val FormatVersion: Int = 1

  final case class Record[Key](
    protocol: String,
    formatVersion: Int,
    key: Key,
    lock: CertifiedVoteLock
  )

  object Record {
    implicit def encoder[Key: Encoder]: Encoder[Record[Key]] = deriveEncoder
    implicit def decoder[Key: Decoder]: Decoder[Record[Key]] = deriveDecoder
  }

  def noop[F[_]: Applicative, Key]: CertifiedVoteLockPersistence[F, Key] =
    new CertifiedVoteLockPersistence[F, Key] {
      def read(key: Key): F[Option[CertifiedVoteLock]] = none[CertifiedVoteLock].pure[F]
      def write(key: Key, lock: CertifiedVoteLock): F[Unit] = Applicative[F].unit
      def delete(key: Key): F[Unit] = Applicative[F].unit
      def deleteAtOrBelow(key: Key): F[Unit] = Applicative[F].unit
      def deleteAbove(key: Key): F[Unit] = Applicative[F].unit
    }

  /** Create the production journal used by both L0 layers.
    *
    * A successful write means the bytes and file metadata were forced, the file was atomically renamed in the same directory, and the
    * directory entry was forced where the platform supports directory fsync. There is deliberately no non-atomic rename fallback.
    */
  def forSnapshotOrdinal[F[_]: Async: JsonSerializer](base: Path): F[CertifiedVoteLockPersistence[F, SnapshotOrdinal]] =
    CrashSafeAtomicFileWriter.make[F](base).map(new SnapshotOrdinalFileSystem[F](base, _))

  private final class SnapshotOrdinalFileSystem[F[_]: Async: JsonSerializer](
    base: Path,
    atomicFileWriter: CrashSafeAtomicFileWriter[F]
  ) extends CertifiedVoteLockPersistence[F, SnapshotOrdinal] {

    private val nioBase = base.toNioPath

    private def fileName(key: SnapshotOrdinal): String = key.value.value.toString
    private def file(key: SnapshotOrdinal) = nioBase.resolve(fileName(key))

    private def validateRecord(expectedKey: SnapshotOrdinal, record: Record[SnapshotOrdinal]): Either[Throwable, CertifiedVoteLock] = {
      val lock = record.lock
      val votedFieldsPaired = lock.highestVotedView.isDefined === lock.votedValueHashAtHighestView.isDefined
      val nonNegativeView = lock.highestVotedView.forall(_ >= 0L)
      val qcKeyMatches = lock.lockedQc.forall(_.value.key === expectedKey.value.value)
      val qcStructure = lock.lockedQc.toList.traverse_(qc => CertifiedConsensus.ProposalValue.validate(qc.value).leftMap(new Exception(_)))

      for {
        _ <- Either.cond(record.protocol === Protocol, (), new IllegalStateException(s"unexpected protocol=${record.protocol}"))
        _ <- Either.cond(
          record.formatVersion === FormatVersion,
          (),
          new IllegalStateException(s"unexpected formatVersion=${record.formatVersion}")
        )
        _ <- Either.cond(record.key === expectedKey, (), new IllegalStateException(s"journal key mismatch: ${record.key} != $expectedKey"))
        _ <- Either.cond(votedFieldsPaired, (), new IllegalStateException("certified vote fields must be both present or both absent"))
        _ <- Either.cond(nonNegativeView, (), new IllegalStateException("certified vote view must be non-negative"))
        _ <- Either.cond(qcKeyMatches, (), new IllegalStateException("certified QC key does not match journal key"))
        _ <- qcStructure
      } yield lock
    }

    def read(key: SnapshotOrdinal): F[Option[CertifiedVoteLock]] =
      Async[F]
        .blocking(JFiles.exists(file(key)))
        .ifM(
          Async[F]
            .blocking(JFiles.readAllBytes(file(key)))
            .flatMap(JsonSerializer[F].deserialize[Record[SnapshotOrdinal]])
            .flatMap(_.leftMap(error => new IllegalStateException(s"corrupt certified vote-lock journal for key=$key", error)).liftTo[F])
            .flatMap(record => validateRecord(key, record).liftTo[F])
            .map(_.some),
          none[CertifiedVoteLock].pure[F]
        )

    def write(key: SnapshotOrdinal, lock: CertifiedVoteLock): F[Unit] = {
      val record = Record(Protocol, FormatVersion, key, lock)

      validateRecord(key, record).liftTo[F] >>
        JsonSerializer[F].serialize(record).flatMap(atomicFileWriter.write(fileName(key), _))
    }

    def delete(key: SnapshotOrdinal): F[Unit] =
      atomicFileWriter.delete(fileName(key)).void

    def deleteAtOrBelow(key: SnapshotOrdinal): F[Unit] =
      deleteMatching(_ <= key.value.value)

    def deleteAbove(key: SnapshotOrdinal): F[Unit] =
      deleteMatching(_ > key.value.value)

    private def deleteMatching(matches: Long => Boolean): F[Unit] =
      Async[F].blocking {
        val entries = JFiles.list(nioBase)
        try
          entries
            .filter(path => path.getFileName.toString.toLongOption.exists(matches))
            .toArray
            .toList
            .map(_.asInstanceOf[java.nio.file.Path])
        finally entries.close()
      }.flatMap { paths =>
        paths.traverse_(path => atomicFileWriter.delete(path.getFileName.toString).void) >> atomicFileWriter.syncDirectory
      }
  }
}
