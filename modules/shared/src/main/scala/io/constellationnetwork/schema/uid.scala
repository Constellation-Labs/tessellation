package io.constellationnetwork.schema

import cats.Functor
import cats.syntax.functor._

import io.constellationnetwork.effects.GenUUID
import io.constellationnetwork.optics.IsUUID

object uid {

  def make[F[_]: Functor: GenUUID, A: IsUUID]: F[A] =
    GenUUID[F].make.map(IsUUID[A]._UUID.get)

  def read[F[_]: Functor: GenUUID, A: IsUUID](str: String): F[A] =
    GenUUID[F].read(str).map(IsUUID[A]._UUID.get)
}
