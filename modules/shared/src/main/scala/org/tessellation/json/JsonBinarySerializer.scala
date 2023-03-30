package org.tessellation.json

import scala.util.Try

import io.circe.jawn.JawnParser
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
object JsonBinarySerializer {
  def serialize[A: Encoder](content: A): Either[Throwable, Array[Byte]] =
    Try(content.asJson.noSpaces.getBytes("UTF-8")).toEither

  def deserialize[A: Decoder](content: Array[Byte]): Either[Throwable, A] =
    JawnParser(false).decodeByteArray(content)
}
