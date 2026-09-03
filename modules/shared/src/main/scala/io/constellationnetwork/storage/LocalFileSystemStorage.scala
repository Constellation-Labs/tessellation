package io.constellationnetwork.storage

import java.io.{File => JFile}
import java.nio.file.{FileAlreadyExistsException, NoSuchFileException}

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.json.JsonSerializer

import better.files._
import fs2.Stream
import fs2.io.file.Path
import io.circe.{Decoder, Encoder}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class SerializableLocalFileSystemStorage[F[_]: JsonSerializer, A: Encoder: Decoder](
  baseDir: Path
)(
  implicit F: Async[F]
) extends LocalFileSystemStorage[F, A](baseDir)
    with SerializableFileSystemStorage[F, A] {

  override val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass(this.getClass)

  def deserializeFallback(bytes: Array[Byte]): Either[Throwable, A]

  def read(fileName: String): F[Option[A]] =
    readBytes(fileName).flatMap {
      _.flatTraverse { bytes =>
        def useFallback(jsonErr: Throwable): F[Option[A]] =
          logger.warn(
            s"Failed to deserialize $fileName - JSON error: ${jsonErr.getMessage}, using the fallback deserializer..."
          ) >>
            F.delay(deserializeFallback(bytes)).attempt.map(_.flatten).flatMap {
              case Right(value) => value.some.pure[F]
              case Left(fallbackErr) =>
                logger.warn(
                  s"Failed to deserialize $fileName - Fallback error: ${fallbackErr.getMessage}"
                ) >> none[A].pure[F]
            }

        JsonSerializer[F].deserialize[A](bytes).attempt.flatMap {
          case Right(Right(value)) => value.some.pure[F]
          case Right(Left(error))  => useFallback(error)
          case Left(error)         => useFallback(error)
        }
      }
    }

  def write(fileName: String, a: A): F[Unit] =
    JsonSerializer[F].serialize(a).flatMap(write(fileName, _))

}

abstract class LocalFileSystemStorage[F[_], A](baseDir: Path)(
  implicit F: Async[F]
) extends FileSystemStorage[F, A]
    with DiskSpace[F] {

  val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

  protected lazy val dir: F[File] = F.blocking {
    baseDir.toNioPath
  }

  private lazy val jDir: F[JFile] = dir.flatMap { a =>
    F.blocking(a.toJava)
  }

  def createDirectoryIfNotExists(): EitherT[F, Throwable, Unit] =
    dir.flatMap { a =>
      F.blocking(a.createDirectoryIfNotExists())
    }.void.attemptT

  def exists(fileName: String): F[Boolean] = dir.flatMap { a =>
    F.blocking((a / fileName).exists)
  }.handleErrorWith {
    case _: NoSuchFileException => false.pure[F]
    case e                      => F.raiseError(e)
  }

  def readBytes(fileName: String): F[Option[Array[Byte]]] =
    dir
      .map(_ / fileName)
      .flatMap { a =>
        F.blocking(a.loadBytes).map(_.some)
      }
      .recover {
        case _: NoSuchFileException => none[Array[Byte]]
      }

  def write(fileName: String, bytes: Array[Byte]): F[Unit] =
    dir
      .map(_ / fileName)
      .flatMap { a =>
        F.blocking(a.parent.createDirectoryIfNotExists(createParents = true)) >>
          F.blocking(a.writeByteArray(bytes))
      }
      .void

  def link(fileName: String, to: String): F[Unit] =
    (dir.map(_ / fileName), dir.map(_ / to)).mapN {
      case (src, dst) =>
        F.blocking(dst.parent.createDirectoryIfNotExists(createParents = true)) >>
          F.blocking(src.linkTo(dst)).void
    }.flatten.handleErrorWith {
      case _: NoSuchFileException =>
        logger.warn(s"Cannot link $fileName to $to: source file does not exist or was removed")
      case _: FileAlreadyExistsException =>
        logger.debug(s"Link $to already exists, skipping")
      case e => F.raiseError(e)
    }

  def move(fileName: String, to: File): F[Unit] =
    dir
      .map(_ / fileName)
      .flatMap { src =>
        F.blocking(to.parent.createDirectoryIfNotExists(createParents = true)) >>
          F.blocking(src.moveTo(to)(File.CopyOptions(overwrite = true))).void
      }
      .handleErrorWith {
        case _: NoSuchFileException =>
          logger.warn(s"Cannot move $fileName: source file does not exist or was removed")
        case e => F.raiseError(e)
      }

  def getPath(fileName: String): F[File] =
    dir.map(_ / fileName)

  def delete(fileName: String): F[Unit] =
    dir
      .map(_ / fileName)
      .flatMap { a =>
        F.blocking(a.delete())
      }
      .void
      .handleErrorWith {
        case _: NoSuchFileException => logger.info("File does not exist or already removed, skipping")
        case e                      => F.raiseError(e)
      }

  def getUsableSpace: F[Long] = jDir.flatMap { a =>
    F.blocking(a.getUsableSpace())
  }

  def getOccupiedSpace: F[Long] = dir.flatMap { a =>
    F.blocking(a.size)
  }

  def listFiles: F[Stream[F, File]] =
    dir
      .flatMap(a => F.blocking(a.list(f => !f.isDirectory, maxDepth = 1)))
      .map { iterator =>
        Stream.fromIterator(iterator, 1).handleErrorWith {
          case _: java.io.UncheckedIOException => Stream.empty
          case e                               => Stream.raiseError(e)
        }
      }

  def findFiles(condition: File => Boolean): F[Stream[F, File]] =
    dir
      .flatMap(a => F.blocking(a.list(f => !f.isDirectory && condition(f), maxDepth = 1)))
      .map { iterator =>
        Stream.fromIterator(iterator, 1).handleErrorWith {
          case _: java.io.UncheckedIOException => Stream.empty
          case e                               => Stream.raiseError(e)
        }
      }
}
