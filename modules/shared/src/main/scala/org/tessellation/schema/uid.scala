package org.tessellation.schema

import cats.Functor
import cats.syntax.functor._

import org.tessellation.optics.IsUUID
import org.tessellation.sdk.effects.GenUUID

object uid {

  def make[F[_]: Functor: GenUUID, A: IsUUID]: F[A] =
    GenUUID[F].make.map(IsUUID[A]._UUID.get)

  def read[F[_]: Functor: GenUUID, A: IsUUID](str: String): F[A] =
    GenUUID[F].read(str).map(IsUUID[A]._UUID.get)
}
