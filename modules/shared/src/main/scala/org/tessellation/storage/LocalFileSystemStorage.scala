package org.tessellation.storage

import java.io.{File => JFile}
import java.nio.file.NoSuchFileException

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._

import scala.reflect.ClassTag

import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer

import better.files._
import fs2.io.file.Path

abstract class LocalFileSystemStorage[F[_]: KryoSerializer, A <: AnyRef: ClassTag](baseDir: Path)(
  implicit F: Async[F]
) extends FileSystemStorage[F, A]
    with DiskSpace[F] {

  private lazy val dir: F[File] = F.delay {
    baseDir.toNioPath
  }

  private lazy val jDir: F[JFile] = dir.flatMap { a =>
    F.delay { a.toJava }
  }

  def createDirectoryIfNotExists(): EitherT[F, Throwable, Unit] =
    dir.flatMap { a =>
      F.delay { a.createDirectoryIfNotExists() }
    }.void.attemptT

  def exists(fileName: String): F[Boolean] = dir.flatMap { a =>
    F.delay { (a / fileName).exists }
  }

  def read(fileName: String): F[Option[A]] =
    readBytes(fileName)
      .flatMap(_.traverse(_.fromBinaryF))

  def readBytes(fileName: String): F[Option[Array[Byte]]] =
    dir
      .map(_ / fileName)
      .flatMap { a =>
        F.delay { a.loadBytes }.map(_.some)
      }
      .recover {
        case _: NoSuchFileException => none[Array[Byte]]
      }

  def write(fileName: String, a: A): F[Unit] =
    a.toBinaryF.flatMap { write(fileName, _) }

  def write(fileName: String, bytes: Array[Byte]): F[Unit] =
    dir
      .map(_ / fileName)
      .flatMap { a =>
        F.delay { a.writeByteArray(bytes) }
      }
      .void

  def link(fileName: String, to: String): F[Unit] =
    (dir.map(_ / fileName), dir.map(_ / to)).mapN {
      case (src, dst) => F.delay { dst.linkTo(src) }.void
    }.flatten

  def delete(fileName: String): F[Unit] =
    dir
      .map(_ / fileName)
      .flatMap { a =>
        F.delay { a.delete() }
      }
      .void

  def getUsableSpace: F[Long] = jDir.flatMap { a =>
    F.delay { a.getUsableSpace() }
  }

  def getOccupiedSpace: F[Long] = dir.flatMap { a =>
    F.delay { a.size }
  }
}
