package io.constellationnetwork.node.shared.infrastructure.storage

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files => JFiles, _}

import cats.effect.Async
import cats.syntax.all._

import fs2.io.file.Path

/** Crash-safe atomic replacement for node-local byte files.
  *
  * A successful write means the temporary file contents and metadata were forced, the destination was atomically replaced in the same
  * directory, and the directory entry was forced where the platform supports directory fsync. There is deliberately no non-atomic rename
  * fallback. Consensus safety journals and the certified-outcome sidecars share this primitive so their durability contract cannot drift.
  */
final class CrashSafeAtomicFileWriter[F[_]: Async] private (base: Path) {

  private val nioBase = base.toNioPath

  private def destination(fileName: String): java.nio.file.Path = {
    val resolved = nioBase.resolve(fileName)
    require(
      resolved.getParent.equals(nioBase) && resolved.getFileName.toString === fileName,
      s"invalid atomic file name=$fileName"
    )
    resolved
  }

  def write(fileName: String, bytes: Array[Byte]): F[Unit] = {
    val target = destination(fileName)

    Async[F].bracketCase(Async[F].blocking(JFiles.createTempFile(nioBase, s".atomic-$fileName.", ".tmp"))) { temp =>
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
            throw new IllegalStateException("atomic move is required for crash-safe local consensus storage", error)
        }
      } >> forceDirectory
    } {
      case (_, cats.effect.kernel.Outcome.Succeeded(_)) => Async[F].unit
      case (temp, _) => delete(temp.getFileName.toString).void.handleError(_ => ())
    }
  }

  /** Durably remove a file. The directory is forced even when the file is already absent, so retrying a cancellation that landed between
    * unlink and directory fsync still establishes a durable cleanup boundary. A crash that resurrects an already-finalized safety lock is
    * conservative, but sharing this rule keeps lifecycle behavior explicit and avoids platform-dependent journal retention.
    */
  def delete(fileName: String): F[Boolean] =
    Async[F].blocking(JFiles.deleteIfExists(destination(fileName))).flatTap(_ => forceDirectory)

  /** Establish a durable directory-metadata boundary after a retried bulk cleanup, including when an earlier cancelled attempt already
    * made every target absent from the directory listing.
    */
  def syncDirectory: F[Unit] = forceDirectory

  private[storage] def initialize: F[Unit] =
    Async[F].blocking(JFiles.createDirectories(nioBase)).void >> forceDirectory

  private def forceDirectory: F[Unit] =
    Async[F].blocking {
      try {
        val directory = FileChannel.open(nioBase, StandardOpenOption.READ)
        try directory.force(true)
        finally directory.close()
      } catch {
        // Some filesystems/JDKs do not permit opening a directory as a channel. File writes still require an atomic move; only this
        // platform-specific directory-metadata force is optional.
        case _: UnsupportedOperationException => ()
        case _: AccessDeniedException if System.getProperty("os.name").toLowerCase.contains("win") => ()
      }
    }
}

object CrashSafeAtomicFileWriter {
  def make[F[_]: Async](base: Path): F[CrashSafeAtomicFileWriter[F]] = {
    val writer = new CrashSafeAtomicFileWriter[F](base)
    writer.initialize.as(writer)
  }
}
