package org.tessellation.schema

import cats.syntax.either._

import scala.util.Try

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.NonNegBigInt
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._
import io.getquill.MappedEncoding

object balance {

  @derive(decoder, encoder, eqv, show)
  @newtype
  case class Balance(value: NonNegBigInt)

  object Balance {
    val empty: Balance = Balance(NonNegBigInt.unsafeFrom(BigInt(0)))

    implicit val quillEncode: MappedEncoding[Balance, String] =
      MappedEncoding[Balance, String](_.coerce.value.toString())
    implicit val quillDecode: MappedEncoding[String, Balance] = MappedEncoding[String, Balance](
      strBalance =>
        Try(BigInt.apply(strBalance)).toEither
          .flatMap(refineV[NonNegative].apply[BigInt](_).leftMap(new Throwable(_))) match {
          //TODO: look at quill Decode for Address
          case Left(e)  => throw e
          case Right(b) => Balance(b)
        }
    )
  }

}
