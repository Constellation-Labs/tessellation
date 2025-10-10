package io.constellationnetwork.security.mpt

import cats.data.Validated
import cats.syntax.bifunctor._
import cats.syntax.foldable._
import cats.syntax.traverse._

import scala.collection.immutable.ArraySeq

import io.constellationnetwork.security.hex.Hex

import io.circe._
import io.circe.syntax.EncoderOps

class Nibble private (val value: Byte) extends AnyVal {

  override def toString: String = "" + Nibble.hexChars(value & 0x0f)
}

object Nibble {
  val empty: Nibble = new Nibble(0: Byte)

  private val hexChars: Array[Char] = "0123456789abcdef".toCharArray

  private def charToByteValue(char: Char): Option[Byte] = char match {
    case c if c >= '0' && c <= '9' => Some((c - '0').toByte)
    case c if c >= 'a' && c <= 'f' => Some((c - 'a' + 10).toByte)
    case c if c >= 'A' && c <= 'F' => Some((c - 'A' + 10).toByte)
    case _                         => None
  }

  def apply(hex: Hex): Seq[Nibble] =
    hex.value.view.map(unsafe).to(ArraySeq)

  def apply(bytes: Array[Byte]): Seq[Nibble] =
    bytes.view.flatMap(apply).to(ArraySeq)

  def apply(byte: Byte): Seq[Nibble] =
    Seq(
      Nibble.unsafe((byte >> 4 & 0x0f).toByte),
      Nibble.unsafe((byte & 0x0f).toByte)
    )

  def fromHexString(hexString: String): Either[InvalidNibble, Seq[Nibble]] = {
    val builder = ArraySeq.newBuilder[Nibble]
    builder.sizeHint(hexString.length)

    hexString.toCharArray.toList
      .foldM(builder) { (acc, char) =>
        charToByteValue(char) match {
          case Some(byteValue) => Right(acc += Nibble.unsafe(byteValue))
          case None            => Left(CharOutOfRange)
        }
      }
      .map(_.result())
  }

  def unsafe(byte: Byte): Nibble = new Nibble(byte)

  def unsafe(char: Char): Nibble =
    charToByteValue(char) match {
      case Some(byteValue) => Nibble.unsafe(byteValue)
      case None            => throw new IllegalArgumentException("Invalid character: " + char)
    }

  def toBytes(nibbles: Seq[Nibble]): Array[Byte] =
    nibbles
      .grouped(2)
      .collect {
        case Seq(high, low) =>
          ((high.value << 4) | (low.value & 0x0f)).toByte
      }
      .toArray

  def validated(byte: Byte): Validated[InvalidNibble, Nibble] =
    if (byte >= 0 && byte <= 15) Validated.valid(new Nibble(byte))
    else Validated.invalid(ByteOutOfRange)

  def validated(c: Char): Validated[InvalidNibble, Nibble] =
    charToByteValue(c) match {
      case Some(byteValue) => Validated.valid(Nibble.unsafe(byteValue))
      case None            => Validated.invalid(CharOutOfRange)
    }

  def commonPrefix(a: Seq[Nibble], b: Seq[Nibble]): Seq[Nibble] =
    a.zip(b)
      .takeWhile(Function.tupled(_.value == _.value))
      .map(_._1)

  implicit val nibbleEncoder: Encoder[Nibble] = (a: Nibble) => Json.fromString("" + hexChars(a.value & 0x0f))

  implicit val nibbleDecoder: Decoder[Nibble] = (c: HCursor) =>
    c.as[String].flatMap { res =>
      if (res.length == 1) validated(res.charAt(0)).toEither.leftMap(err => DecodingFailure(err.toString, c.history))
      else Left(DecodingFailure("Nibble string must be of length 1", c.history))
    }

  implicit val nibbleSeqEncoder: Encoder[Seq[Nibble]] =
    (seq: Seq[Nibble]) => seq.map(a => hexChars(a.value & 0x0f)).mkString("").asJson

  implicit val nibbleSeqDecoder: Decoder[Seq[Nibble]] = (c: HCursor) =>
    c.as[String].flatMap { res =>
      res.toList.traverse(validated(_).toEither.leftMap(err => DecodingFailure(err.toString, c.history)))
    }

  implicit val nibbleKeyEncoder: KeyEncoder[Nibble] = (key: Nibble) => "" + hexChars(key.value & 0x0f)

  implicit val nibbleKeyDecoder: KeyDecoder[Nibble] = (key: String) => if (key.length == 1) validated(key.charAt(0)).toOption else None
}

sealed trait InvalidNibble extends Throwable
case object ByteOutOfRange extends InvalidNibble
case object CharOutOfRange extends InvalidNibble