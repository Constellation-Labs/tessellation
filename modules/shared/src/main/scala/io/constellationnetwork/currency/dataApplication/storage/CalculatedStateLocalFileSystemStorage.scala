package io.constellationnetwork.currency.dataApplication.storage

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files => JFiles, _}

import cats.effect.kernel.Async
import cats.syntax.all._

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.storage.LocalFileSystemStorage

import fs2.Stream
import fs2.io.file.Path

final class CalculatedStateLocalFileSystemStorage[F[_]: Async] private (
  path: Path
) extends LocalFileSystemStorage[F, Array[Byte]](path) {
  def read[A](ordinal: SnapshotOrdinal)(implicit decoder: Array[Byte] => F[A]): F[Option[A]] =
    readBytes(toOrdinalName(ordinal)).flatMap {
      _.traverse(decoder)
    }

  def exists(ordinal: SnapshotOrdinal): F[Boolean] =
    exists(toOrdinalName(ordinal))

  def write[A](ordinal: SnapshotOrdinal, state: A)(implicit encoder: A => F[Array[Byte]]): F[Unit] =
    encoder(state).flatMap(write(toOrdinalName(ordinal), _))

  def writeAtomically[A](ordinal: SnapshotOrdinal, state: A)(implicit encoder: A => F[Array[Byte]]): F[Unit] =
    encoder(state).flatMap(writeAtomicBytes(toOrdinalName(ordinal), _))

  def delete[A](ordinal: SnapshotOrdinal): F[Unit] =
    delete(toOrdinalName(ordinal))

  def listStoredOrdinals: F[Stream[F, SnapshotOrdinal]] =
    listFiles.map {
      _.map(_.name)
        .map(_.toLongOption)
        .map(_.flatMap(SnapshotOrdinal(_)))
        .collect {
          case Some(a) => a
        }
    }

  def deleteAbove(ordinal: SnapshotOrdinal): F[Unit] =
    listStoredOrdinals.flatMap {
      _.filter(_ > ordinal)
        .evalMap(delete)
        .compile
        .drain
    }

  private def toOrdinalName(ordinal: SnapshotOrdinal): String = ordinal.value.value.toString

  private def writeAtomicBytes(fileName: String, bytes: Array[Byte]): F[Unit] = {
    val directory = path.toNioPath
    val target = directory.resolve(fileName)

    Async[F].bracketCase(Async[F].blocking(JFiles.createTempFile(directory, s".atomic-$fileName.", ".tmp"))) { temp =>
      Async[F].blocking {
        val channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        try {
          val buffer = ByteBuffer.wrap(bytes)
          while (buffer.hasRemaining) channel.write(buffer)
          channel.force(true)
        } finally channel.close()

        try
          JFiles.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        catch {
          case error: AtomicMoveNotSupportedException =>
            throw new IllegalStateException("atomic move is required for calculated-state recovery", error)
        }

        try {
          val directoryChannel = FileChannel.open(directory, StandardOpenOption.READ)
          try directoryChannel.force(true)
          finally directoryChannel.close()
        } catch {
          case _: UnsupportedOperationException                                                      => ()
          case _: AccessDeniedException if System.getProperty("os.name").toLowerCase.contains("win") => ()
        }
      }
    } {
      case (_, cats.effect.kernel.Outcome.Succeeded(_)) => Async[F].unit
      case (temp, _)                                    => Async[F].blocking(JFiles.deleteIfExists(temp)).void.handleError(_ => ())
    }
  }
}

object CalculatedStateLocalFileSystemStorage {
  private val AbandonedAtomicTempFile = raw"\.atomic-\d+\..+\.tmp".r

  private[storage] def cleanupAbandonedAtomicTemps[F[_]: Async](path: Path): F[Unit] =
    Async[F].blocking {
      val directory = JFiles.newDirectoryStream(path.toNioPath)

      try {
        val iterator = directory.iterator()

        while (iterator.hasNext) {
          val candidate = iterator.next()
          val isGeneratedAtomicTemp = AbandonedAtomicTempFile.matches(candidate.getFileName.toString)

          if (isGeneratedAtomicTemp && JFiles.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
            JFiles.deleteIfExists(candidate)
        }
      } finally directory.close()
    }.void

  def make[F[_]: Async](path: Path): F[CalculatedStateLocalFileSystemStorage[F]] =
    new CalculatedStateLocalFileSystemStorage[F](path).pure[F].flatTap { storage =>
      storage.createDirectoryIfNotExists().rethrowT >> cleanupAbandonedAtomicTemps(path)
    }
}
