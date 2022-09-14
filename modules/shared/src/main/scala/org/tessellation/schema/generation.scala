package org.tessellation.schema

import cats.MonadThrow
import cats.effect.Clock
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import derevo.scalacheck.arbitrary
import eu.timepit.refined.scalacheck.numeric._
import eu.timepit.refined.types.numeric.PosLong
import io.estatico.newtype.macros.newtype

object generation {
  @derive(arbitrary, decoder, encoder, order, show)
  @newtype
  case class Generation(value: PosLong)

  object Generation {
    val MinValue = Generation(PosLong.MinValue)

    def make[F[_]: Clock: MonadThrow]: F[Generation] =
      Clock[F].realTime
        .map(_.toMillis)
        .flatMap { s =>
          PosLong.from(s).leftMap(err => new Throwable(err)).liftTo[F]
        }
        .map(Generation(_))
  }
}
