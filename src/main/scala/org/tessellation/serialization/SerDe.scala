package org.tessellation.serialization

trait SerDe {
  def serialize[T <: Any](obj: T): Either[SerializationError, Array[Byte]]
  def deserialize[T](b: Array[Byte]): Either[SerializationError, T]
}
