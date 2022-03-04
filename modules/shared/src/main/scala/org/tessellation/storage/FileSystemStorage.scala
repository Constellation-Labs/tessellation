package org.tessellation.storage

trait FileSystemStorage[F[_], A] {
  def exists(path: String): F[Boolean]
  def read(path: String): F[Option[A]]
  def readBytes(path: String): F[Option[Array[Byte]]]
  def write(path: String, bytes: Array[Byte]): F[Unit]
  def write(path: String, a: A): F[Unit]
  def delete(path: String): F[Unit]
}
