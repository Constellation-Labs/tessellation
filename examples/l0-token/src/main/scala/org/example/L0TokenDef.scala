package org.example

import cats.effect.{Async, Concurrent, Temporal}
import cats.syntax.all._
import cats.{Applicative, Monad}

import org.example.types._

import eu.timepit.refined.auto._
import higherkindness.droste._
import org.tessellation.kernel.Cell.NullTerminal
import org.tessellation.kernel._
import org.tessellation.schema.address.Address
import org.typelevel.log4cats.slf4j.Slf4jLogger
import fs2.{Pipe, Stream}

import scala.concurrent.duration._

object L0TokenDef extends StateChannelDef[L0TokenStep, Ω, L0TokenStep] {

  override val address = Address("DAG3k3VihUWMjse9LE93jRqZLEuwGd6a5Ypk4zYS")

  override val kryoRegistrar =
    Map(
      classOf[L0TokenTransaction] -> 1000,
      classOf[L0TokenBlock] -> 1001,
      classOf[CreateStateChannelSnapshot] -> 1002
    )

  override def makeCell[F[_]: Async](
    input: L0TokenStep,
    hgContext: HypergraphContext[F]
  ): Cell[F, StackF, L0TokenStep, Either[CellError, Ω], L0TokenStep] =
    new Cell[F, StackF, L0TokenStep, Either[CellError, Ω], L0TokenStep](
      data = input,
      hylo = scheme.hyloM(
        AlgebraM[F, StackF, Either[CellError, Ω]] {
          case More(a)      => a.pure[F]
          case Done(result) => result.pure[F]
        },
        CoalgebraM[F, StackF, Ω] { input =>
          val logger = Slf4jLogger.getLogger[F]

          input match {
            case ProcessSnapshot(snapshot) =>
              logger.info(s"ProcessSnapshot executed") >>
                logger.info(s"Update balances") >>
                Applicative[F].pure(Done(NullTerminal.asRight[CellError]))

            case CreateStateChannelSnapshot() =>
              logger.info(s"Create state-channel snapshot") >>
                Applicative[F].pure(Done(L0TokenStateChannelSnapshot().asRight[CellError]))

            case _ => Applicative[F].pure(Done(CellError("Unhandled coalgebra case").asLeft[Ω]))
          }
        }
      ),
      convert = a => a
    )

  def trigger[F[_]: Async]: Stream[F, L0TokenStep] =
    Stream.awakeEvery(10.seconds).as(CreateStateChannelSnapshot())

  /**
    * The following code will merge two streams alltogether.
    * It pass-through the input stream and emit CreateStateChannelSnapshot() every 10 seconds
    *
    * override def inputPipe[F[_]: Async]: Pipe[F, L0TokenStep, L0TokenStep] = _.merge(trigger$[F])
    */


  /** 
   * This will watch input stream and start emitting CreateStateChannelSnapshot() only if there is no new input for n seconds.
   */
  override def inputPipe[F[_]: Async]: Pipe[F, L0TokenStep, L0TokenStep] = _.switchMap { input =>
    val n = 10.seconds

    val delayed = Stream.sleep(n).flatMap(_ => trigger)

    Stream.emit(input).merge(delayed)
  }

}
