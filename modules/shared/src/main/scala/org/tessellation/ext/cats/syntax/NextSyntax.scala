package org.tessellation.ext.cats

import org.tessellation.ext.cats.kernel._

import _root_.cats.kernel.{Next => catsNext}

trait NextSyntax {
  implicit def catsSyntaxNext[A: catsNext](a: A): NextOps[A] =
    new NextOps[A](a)
}

final class NextOps[A: catsNext](a: A) {
  def next = Next[A].next(a)
}
