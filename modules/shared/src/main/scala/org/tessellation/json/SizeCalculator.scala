package org.tessellation.json

import cats.Functor
import cats.syntax.all._

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegInt
import io.circe.Encoder

object SizeCalculator {
  def bytes[F[_]: Functor: JsonSerializer, A: Encoder](a: A): F[NonNegInt] =
    JsonSerializer[F].serialize(a).map { binary =>
      NonNegInt.unsafeFrom(binary.size)
    }

  def kilobytes[F[_]: Functor: JsonSerializer, A: Encoder](a: A): F[NonNegInt] = bytes(a).map(_.toLong).map(toKilobytes)

  def toKilobytes(bytesSize: Long): NonNegInt =
    NonNegInt.unsafeFrom(
      (BigDecimal(bytesSize) / BigDecimal(1024))
        .setScale(0, BigDecimal.RoundingMode.UP)
        .toInt
    )
}
