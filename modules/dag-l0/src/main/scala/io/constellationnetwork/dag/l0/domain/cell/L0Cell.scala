package io.constellationnetwork.dag.l0.domain.cell

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.dag.l0.domain.cell.AlgebraCommand._
import io.constellationnetwork.dag.l0.domain.cell.CoalgebraCommand._
import io.constellationnetwork.dag.l0.domain.cell.L0Cell.{Algebra, Coalgebra}
import io.constellationnetwork.dag.l0.domain.cell.L0CellInput._
import io.constellationnetwork.dag.l0.domain.delegatedStake.DelegatedStakeOutput
import io.constellationnetwork.dag.l0.domain.nodeCollateral.NodeCollateralOutput
import io.constellationnetwork.kernel.Cell.NullTerminal
import io.constellationnetwork.kernel._
import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.StateChannelOutput

import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}

sealed trait L0CellInput

object L0CellInput {
  case class HandleDAGL1(data: Signed[Block]) extends L0CellInput
  case class HandleStateChannelSnapshot(snapshot: StateChannelOutput) extends L0CellInput
  case class HandleUpdateNodeParameters(updateNodeParameters: Signed[UpdateNodeParameters]) extends L0CellInput
  case class HandleDelegatedStake(delegatedStake: DelegatedStakeOutput) extends L0CellInput
  case class HandleNodeCollateral(nodeCollateral: NodeCollateralOutput) extends L0CellInput
}

class L0Cell[F[_]: Async](
  data: L0CellInput,
  l1OutputQueue: Queue[F, Signed[Block]],
  stateChannelOutputQueue: Queue[F, StateChannelOutput],
  updateNodeParametersQueue: Queue[F, Signed[UpdateNodeParameters]],
  delegatedStakeOutputQueue: Queue[F, DelegatedStakeOutput],
  nodeCollateralOutputQueue: Queue[F, NodeCollateralOutput]
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
              case EnqueueUpdateNodeParameters(updateNodeParameters) =>
                Algebra.enqueueUpdateNodeParameters(updateNodeParametersQueue)(updateNodeParameters)
              case EnqueueDelegatedStake(data) => Algebra.enqueueDelegatedStake(delegatedStakeOutputQueue)(data)
              case EnqueueNodeCollateral(data) => Algebra.enqueueNodeCollateral(nodeCollateralOutputQueue)(data)
              case NoAction =>
                NullTerminal.asRight[CellError].widen[Ω].pure[F]
            }
          case Done(other) => other.pure[F]
        },
        CoalgebraM[F, StackF, CoalgebraCommand] {
          case ProcessDAGL1(data)                                => Coalgebra.processDAGL1(data)
          case ProcessStateChannelSnapshot(snapshot)             => Coalgebra.processStateChannelSnapshot(snapshot)
          case ProcessUpdateNodeParameters(updateNodeParameters) => Coalgebra.processUpdateNodeParameters(updateNodeParameters)
          case ProcessDelegatedStake(data)                       => Coalgebra.processDelegatedStake(data)
          case ProcessNodeCollateral(data)                       => Coalgebra.processNodeCollateral(data)
        }
      ),
      {
        case HandleDAGL1(data)                                => ProcessDAGL1(data)
        case HandleStateChannelSnapshot(snapshot)             => ProcessStateChannelSnapshot(snapshot)
        case HandleUpdateNodeParameters(updateNodeParameters) => ProcessUpdateNodeParameters(updateNodeParameters)
        case HandleDelegatedStake(create)                     => ProcessDelegatedStake(create)
        case HandleNodeCollateral(create)                     => ProcessNodeCollateral(create)
      }
    )

object L0Cell {

  type Mk[F[_]] = L0CellInput => L0Cell[F]

  def mkL0Cell[F[_]: Async](
    l1OutputQueue: Queue[F, Signed[Block]],
    stateChannelOutputQueue: Queue[F, StateChannelOutput],
    updateNodeParametersQueue: Queue[F, Signed[UpdateNodeParameters]],
    delegatedStakeOutputQueue: Queue[F, DelegatedStakeOutput],
    nodeCollateralOutputQueue: Queue[F, NodeCollateralOutput]
  ): Mk[F] =
    data =>
      new L0Cell(
        data,
        l1OutputQueue,
        stateChannelOutputQueue,
        updateNodeParametersQueue,
        delegatedStakeOutputQueue,
        nodeCollateralOutputQueue
      )

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

    def enqueueUpdateNodeParameters[F[_]: Async](queue: Queue[F, Signed[UpdateNodeParameters]])(
      updateNodeParameters: Signed[UpdateNodeParameters]
    ): AlgebraR[F] =
      queue.offer(updateNodeParameters) >>
        NullTerminal.asRight[CellError].widen[Ω].pure[F]

    def enqueueDelegatedStake[F[_]: Async](queue: Queue[F, DelegatedStakeOutput])(
      data: DelegatedStakeOutput
    ): AlgebraR[F] =
      queue.offer(data) >>
        NullTerminal.asRight[CellError].widen[Ω].pure[F]

    def enqueueNodeCollateral[F[_]: Async](queue: Queue[F, NodeCollateralOutput])(
      data: NodeCollateralOutput
    ): AlgebraR[F] =
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

    def processUpdateNodeParameters[F[_]: Async](updateNodeParameters: Signed[UpdateNodeParameters]): CoalgebraR[F] = {
      def res: StackF[CoalgebraCommand] = Done(AlgebraCommand.EnqueueUpdateNodeParameters(updateNodeParameters).asRight[CellError])

      res.pure[F]
    }

    def processDelegatedStake[F[_]: Async](data: DelegatedStakeOutput): CoalgebraR[F] = {
      def res: StackF[CoalgebraCommand] = Done(AlgebraCommand.EnqueueDelegatedStake(data).asRight[CellError])

      res.pure[F]
    }

    def processNodeCollateral[F[_]: Async](data: NodeCollateralOutput): CoalgebraR[F] = {
      def res: StackF[CoalgebraCommand] = Done(AlgebraCommand.EnqueueNodeCollateral(data).asRight[CellError])

      res.pure[F]
    }

  }
}
