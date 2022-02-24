package org.tessellation.storage

import cats.data.EitherT

trait FileSystemStorage[F[_], A] {
  def exists(path: String): F[Boolean]
  def read(path: String): EitherT[F, Throwable, A]
  def readBytes(path: String): EitherT[F, Throwable, Array[Byte]]
  def write(path: String, bytes: Array[Byte]): EitherT[F, Throwable, Unit]
  def write(path: String, a: A): EitherT[F, Throwable, Unit]
  def delete(path: String): EitherT[F, Throwable, Unit]
}
