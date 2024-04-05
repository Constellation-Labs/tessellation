package org.tessellation.currency.l0.cell

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.currency.l0.cell.AlgebraCommand.NoAction
import org.tessellation.kernel.Cell.NullTerminal
import org.tessellation.kernel._
import org.tessellation.node.shared.snapshot.currency.CurrencySnapshotEvent

import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}

import AlgebraCommand.EnqueueCurrencySnapshotEvent
import CoalgebraCommand.ProcessCurrencySnapshotEvent
import L0Cell.{Algebra, Coalgebra}
import L0CellInput.HandleCurrencySnapshotEvent

sealed trait L0CellInput

object L0CellInput {
  case class HandleCurrencySnapshotEvent(data: CurrencySnapshotEvent) extends L0CellInput
}

class L0Cell[F[_]: Async](
  data: L0CellInput,
  l1OutputQueue: Queue[F, CurrencySnapshotEvent]
) extends Cell[F, StackF, L0CellInput, Either[CellError, Ω], CoalgebraCommand](
      data,
      scheme.hyloM(
        AlgebraM[F, StackF, Either[CellError, Ω]] {
          case More(a) => a.pure[F]
          case Done(Right(cmd: AlgebraCommand)) =>
            cmd match {
              case EnqueueCurrencySnapshotEvent(data) =>
                Algebra.enqueueCurrencySnapshotEvent(l1OutputQueue)(data)
              case NoAction =>
                NullTerminal.asRight[CellError].widen[Ω].pure[F]
            }
          case Done(other) => other.pure[F]
        },
        CoalgebraM[F, StackF, CoalgebraCommand] {
          case ProcessCurrencySnapshotEvent(data) => Coalgebra.processCurrencySnapshotEvent(data)
        }
      ),
      {
        case HandleCurrencySnapshotEvent(data) => ProcessCurrencySnapshotEvent(data)
      }
    )

object L0Cell {

  type Mk[F[_]] = L0CellInput => L0Cell[F]

  def mkL0Cell[F[_]: Async](
    l1OutputQueue: Queue[F, CurrencySnapshotEvent]
  ): Mk[F] =
    data => new L0Cell(data, l1OutputQueue)

  type AlgebraR[F[_]] = F[Either[CellError, Ω]]
  type CoalgebraR[F[_]] = F[StackF[CoalgebraCommand]]

  object Algebra {

    def enqueueCurrencySnapshotEvent[F[_]: Async](queue: Queue[F, CurrencySnapshotEvent])(data: CurrencySnapshotEvent): AlgebraR[F] =
      queue.offer(data) >>
        NullTerminal.asRight[CellError].widen[Ω].pure[F]
  }

  object Coalgebra {

    def processCurrencySnapshotEvent[F[_]: Async](data: CurrencySnapshotEvent): CoalgebraR[F] = {
      def res: StackF[CoalgebraCommand] = Done(AlgebraCommand.EnqueueCurrencySnapshotEvent(data).asRight[CellError])

      res.pure[F]
    }
  }
}
