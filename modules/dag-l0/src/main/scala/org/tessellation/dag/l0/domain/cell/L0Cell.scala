package org.tessellation.dag.l0.domain.cell

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.kernel.Cell.NullTerminal
import org.tessellation.kernel._
import org.tessellation.schema.Block
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelOutput

import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}

import AlgebraCommand._
import CoalgebraCommand._
import L0Cell.{Algebra, Coalgebra}
import L0CellInput._

sealed trait L0CellInput

object L0CellInput {
  case class HandleDAGL1(data: Signed[Block]) extends L0CellInput
  case class HandleStateChannelSnapshot(snapshot: StateChannelOutput) extends L0CellInput
}

class L0Cell[F[_]: Async](
  data: L0CellInput,
  l1OutputQueue: Queue[F, Signed[Block]],
  stateChannelOutputQueue: Queue[F, StateChannelOutput]
) extends Cell[F, StackF, L0CellInput, Either[CellError, Ω], CoalgebraCommand](
      data,
      scheme.hyloM(
        AlgebraM[F, StackF, Either[CellError, Ω]] {
          case More(a) => a.pure[F]
          case Done(Right(cmd: AlgebraCommand)) =>
            cmd match {
              case EnqueueStateChannelSnapshot(snapshot) =>
                Algebra.enqueueStateChannelSnapshot(stateChannelOutputQueue)(snapshot)
              case EnqueueDAGL1Data(data) =>
                Algebra.enqueueDAGL1Data(l1OutputQueue)(data)
              case NoAction =>
                NullTerminal.asRight[CellError].widen[Ω].pure[F]
            }
          case Done(other) => other.pure[F]
        },
        CoalgebraM[F, StackF, CoalgebraCommand] {
          case ProcessDAGL1(data)                    => Coalgebra.processDAGL1(data)
          case ProcessStateChannelSnapshot(snapshot) => Coalgebra.processStateChannelSnapshot(snapshot)
        }
      ),
      {
        case HandleDAGL1(data)                    => ProcessDAGL1(data)
        case HandleStateChannelSnapshot(snapshot) => ProcessStateChannelSnapshot(snapshot)
      }
    )

object L0Cell {

  type Mk[F[_]] = L0CellInput => L0Cell[F]

  def mkL0Cell[F[_]: Async](
    l1OutputQueue: Queue[F, Signed[Block]],
    stateChannelOutputQueue: Queue[F, StateChannelOutput]
  ): Mk[F] =
    data => new L0Cell(data, l1OutputQueue, stateChannelOutputQueue)

  type AlgebraR[F[_]] = F[Either[CellError, Ω]]
  type CoalgebraR[F[_]] = F[StackF[CoalgebraCommand]]

  object Algebra {

    def enqueueStateChannelSnapshot[F[_]: Async](
      queue: Queue[F, StateChannelOutput]
    )(snapshot: StateChannelOutput): AlgebraR[F] =
      queue.offer(snapshot) >>
        NullTerminal.asRight[CellError].widen[Ω].pure[F]

    def enqueueDAGL1Data[F[_]: Async](queue: Queue[F, Signed[Block]])(data: Signed[Block]): AlgebraR[F] =
      queue.offer(data) >>
        NullTerminal.asRight[CellError].widen[Ω].pure[F]

  }

  object Coalgebra {

    def processDAGL1[F[_]: Async](data: Signed[Block]): CoalgebraR[F] = {
      def res: StackF[CoalgebraCommand] = Done(AlgebraCommand.EnqueueDAGL1Data(data).asRight[CellError])

      res.pure[F]
    }

    def processStateChannelSnapshot[F[_]: Async](snapshot: StateChannelOutput): CoalgebraR[F] = {
      def res: StackF[CoalgebraCommand] = Done(AlgebraCommand.EnqueueStateChannelSnapshot(snapshot).asRight[CellError])

      res.pure[F]
    }

  }
}
