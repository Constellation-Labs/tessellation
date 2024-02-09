package org.tessellation.storage

trait FileSystemStorage[F[_], A] {
  def exists(path: String): F[Boolean]
  def readBytes(path: String): F[Option[Array[Byte]]]
  def write(path: String, bytes: Array[Byte]): F[Unit]
  def delete(path: String): F[Unit]
}

trait SerializableFileSystemStorage[F[_], A] {
  def read(path: String): F[Option[A]]
  def write(path: String, a: A): F[Unit]
}
