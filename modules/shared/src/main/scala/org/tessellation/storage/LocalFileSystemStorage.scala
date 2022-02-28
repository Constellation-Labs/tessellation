package org.tessellation.storage

import java.io.{File => JFile}

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.reflect.ClassTag

import org.tessellation.kryo.KryoSerializer

import better.files._
import fs2.io.file.Path

abstract class LocalFileSystemStorage[F[_]: KryoSerializer, A <: AnyRef: ClassTag](baseDir: Path)(implicit F: Async[F])
    extends FileSystemStorage[F, A]
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

  def read(fileName: String): EitherT[F, Throwable, A] = readBytes(fileName).flatMap { bytes =>
    EitherT.fromEither { KryoSerializer[F].deserialize[A](bytes) }
  }

  def readBytes(fileName: String): EitherT[F, Throwable, Array[Byte]] =
    dir
      .map(_ / fileName)
      .flatMap { a =>
        F.delay { a.loadBytes }
      }
      .attemptT

  def write(fileName: String, a: A): EitherT[F, Throwable, Unit] =
    EitherT.fromEither { KryoSerializer[F].serialize(a) }.flatMap { write(fileName, _) }

  def write(fileName: String, bytes: Array[Byte]): EitherT[F, Throwable, Unit] =
    dir
      .map(_ / fileName)
      .flatMap { a =>
        F.delay { a.writeByteArray(bytes) }
      }
      .void
      .attemptT

  def delete(fileName: String): EitherT[F, Throwable, Unit] =
    dir
      .map(_ / fileName)
      .flatMap { a =>
        F.delay { a.delete() }
      }
      .void
      .attemptT

  def getUsableSpace: F[Long] = jDir.flatMap { a =>
    F.delay { a.getUsableSpace() }
  }

  def getOccupiedSpace: F[Long] = dir.flatMap { a =>
    F.delay { a.size }
  }
}
