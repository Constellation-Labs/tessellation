package org.tessellation.json

import io.circe.jawn.JawnParser
import io.circe.syntax._
import io.circe.{Decoder, Encoder}

object JsonBinarySerializer {
  def serialize[A: Encoder](content: A): Array[Byte] =
    content.asJson.noSpaces.getBytes("UTF-8")

  def deserialize[A: Decoder](content: Array[Byte]): Either[Throwable, A] =
    JawnParser(false).decodeByteArray(content)
}
