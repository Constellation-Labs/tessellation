package org.tessellation.schema

import cats.MonadThrow
import cats.effect.Clock
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import derevo.cats.{order, show}
import derevo.circe.magnolia._
import derevo.derive
import derevo.scalacheck.arbitrary
import eu.timepit.refined.scalacheck.numeric._
import eu.timepit.refined.types.numeric.PosLong
import io.estatico.newtype.macros.newtype

object generation {
  @derive(arbitrary, order, show, encoder, decoder, keyEncoder, keyDecoder)
  @newtype
  case class Generation(value: PosLong)

  object Generation {
    val MinValue: Generation = Generation(PosLong.MinValue)

    def make[F[_]: Clock: MonadThrow]: F[Generation] =
      Clock[F].realTime
        .map(_.toMillis)
        .flatMap { s =>
          PosLong.from(s).leftMap(err => new Throwable(err)).liftTo[F]
        }
        .map(Generation(_))
  }
}
