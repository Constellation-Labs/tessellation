package org.tessellation.kernel

import cats._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}

class Cell[M[_], F[_], A, B, S](val data: A, val hylo: S => M[B], val convert: A => S) extends Hom[A, B] { // TODO: was Topos but we aren't using it yet
  // extends Topos[A, B] {
  def run(): M[B] = hylo(convert(data))
}

object Cell {
  case object NullTerminal extends Ω

  def unapply[M[_], F[_], A, B, S](cell: Cell[M, F, A, B, S]): Some[(A, S => M[B])] = Some((cell.data, cell.hylo))

  implicit def toCell[M[_], F[_], A <: Cell[M, F, Ω, Either[CellError, Ω], Ω]](
    a: A
  ): Cell[M, F, Ω, Either[CellError, Ω], Ω] = a.asInstanceOf[Cell[M, F, Ω, Either[CellError, Ω], Ω]]

  implicit def cellMonoid[M[_], F[_]: Applicative](
    implicit M: Monad[M],
    F: Traverse[F]
  ): Monoid[Cell[M, F, Ω, Either[CellError, Ω], Ω]] =
    new Monoid[Cell[M, F, Ω, Either[CellError, Ω], Ω]] {

      override def empty: Cell[M, F, Ω, Either[CellError, Ω], Ω] = {
        val algebra = AlgebraM[M, F, Either[CellError, Ω]] { _ =>
          M.pure(NullTerminal.asInstanceOf[Ω].asRight[CellError])
        }

        val coalgebra = CoalgebraM[M, F, Ω] { _ =>
          M.compose[F].pure(NullTerminal.asInstanceOf[Ω])
        }

        val hyloM = scheme.hyloM(algebra, coalgebra)

        new Cell[M, F, Ω, Either[CellError, Ω], Ω](
          NullTerminal,
          hyloM,
          _ => NullTerminal
        ) {
          override def run(): M[Either[CellError, Ω]] = M.pure(NullTerminal.asInstanceOf[Ω].asRight[CellError])
        }
      }

      // TODO: A param should be Ω as well to make it possible to combine Cells with different A type
      override def combine(
        x: Cell[M, F, Ω, Either[CellError, Ω], Ω],
        y: Cell[M, F, Ω, Either[CellError, Ω], Ω]
      ): Cell[M, F, Ω, Either[CellError, Ω], Ω] = (x, y) match {
        case (Cell(NullTerminal, _), Cell(NullTerminal, _)) => empty
        case (Cell(NullTerminal, _), yy)                    => yy
        case (xx, Cell(NullTerminal, _))                    => xx
        case (cella @ Cell(a, _), cellb @ Cell(b, _)) =>
          val input: ΩList = a :: b

          val combinedHyloM: Ω => M[Either[CellError, Ω]] = _ =>
            for {
              aOutput <- cella.run()
              bOutput <- cellb.run()
              combinedOutput = aOutput.flatMap(aΩ => bOutput.map(bΩ => aΩ :: bΩ))
            } yield combinedOutput
          new Cell[M, F, Ω, Either[CellError, Ω], Ω](
            input,
            combinedHyloM,
            _ => input
          )
      }
    }
}
