package org.tessellation.serialization

trait SerializationError {
  val reason: Throwable
}

case class SerializationException(val reason: Throwable) extends SerializationError
case class DeserializationException(val reason: Throwable) extends SerializationError
