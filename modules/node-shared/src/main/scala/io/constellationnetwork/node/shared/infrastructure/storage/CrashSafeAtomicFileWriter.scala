package io.constellationnetwork.node.shared.infrastructure.storage

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files => JFiles, _}

import cats.effect.Async
import cats.syntax.all._

import fs2.io.file.Path

/** Crash-safe atomic replacement for node-local byte files. */
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
            throw new IllegalStateException("atomic move is required for crash-safe local snapshot recovery", error)
        }
      } >> forceDirectory
    } {
      case (_, cats.effect.kernel.Outcome.Succeeded(_)) => Async[F].unit
      case (temp, _)                                    => Async[F].blocking(JFiles.deleteIfExists(temp)).void.handleError(_ => ())
    }
  }

  private def initialize: F[Unit] =
    Async[F].blocking(JFiles.createDirectories(nioBase)).void >> forceDirectory

  private def forceDirectory: F[Unit] =
    Async[F].blocking {
      try {
        val directory = FileChannel.open(nioBase, StandardOpenOption.READ)
        try directory.force(true)
        finally directory.close()
      } catch {
        case _: UnsupportedOperationException                                                      => ()
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
