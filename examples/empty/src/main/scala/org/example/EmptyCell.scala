package org.example

import cats.effect.IO
import cats.syntax.all._
import cats.{Applicative, Monad}
import higherkindness.droste._
import org.tessellation.schema.Cell.NullTerminal
import org.tessellation.schema.{Cell, CellError, StackF, Ω}

case class EmptyInput(amount: Int = 0) extends Ω

class EmptyCell(data: Ω)
    extends Cell[IO, StackF, Ω, Either[CellError, Ω], Ω](
      data = data,
      hylo = scheme.hyloM(
        AlgebraM[IO, StackF, Either[CellError, Ω]] { _ =>
          Monad[IO].pure(NullTerminal().asInstanceOf[Ω].asRight[CellError])
        },
        CoalgebraM[IO, StackF, Ω] { _ =>
          Applicative[IO].compose[StackF].pure(EmptyInput().asInstanceOf[Ω])
        }
      ),
      convert = a => a
    ) {}
