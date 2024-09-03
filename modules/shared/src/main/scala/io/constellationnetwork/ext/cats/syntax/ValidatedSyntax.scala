package io.constellationnetwork.ext.cats.syntax

import _root_.cats.Functor
import _root_.cats.data.Validated
import _root_.cats.syntax.functor._

trait ValidatedSyntax {

  implicit def validatedSyntax[F[_]: Functor, E, A](validated: Validated[F[E], A]): ValidatedOps[F, E, A] =
    new ValidatedOps[F, E, A](validated)

}

final class ValidatedOps[F[_]: Functor, E, A](validated: Validated[F[E], A]) {

  def errorMap[EE](fn: E => EE): Validated[F[EE], A] =
    validated.leftMap[F[EE]](_.map(fn))

}
