package org.tesselation.domain

import cats.Functor
import cats.syntax.all._

import org.tesselation.effects.GenUUID
import org.tesselation.optics.IsUUID

object ID {

  def make[F[_]: Functor: GenUUID, A: IsUUID]: F[A] =
    GenUUID[F].make.map(IsUUID[A]._UUID.get)

  def read[F[_]: Functor: GenUUID, A: IsUUID](str: String): F[A] =
    GenUUID[F].read(str).map(IsUUID[A]._UUID.get)
}
