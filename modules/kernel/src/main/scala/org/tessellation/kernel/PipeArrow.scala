package org.tessellation.kernel

import cats.arrow.Arrow
import cats.effect.IO

import fs2.{Pipe, Stream}

object PipeArrow {
  implicit val arrowIOInstance: Arrow[Pipe[IO, *, *]] = new Arrow[Pipe[IO, *, *]] {
    override def lift[A, B](f: A => B): Pipe[IO, A, B] = (a: Stream[IO, A]) => a.map(f)

    override def compose[A, B, C](f: Pipe[IO, B, C], g: Pipe[IO, A, B]): Pipe[IO, A, C] =
      (a: Stream[IO, A]) => f.compose(g)(a)

    override def first[A, B, C](fa: Pipe[IO, A, B]): Pipe[IO, (A, C), (B, C)] =
      (a: Stream[IO, (A, C)]) =>
        a.flatMap {
          case (aa, ac) => fa(Stream(aa)).map((_, ac))
        }
  }
}
