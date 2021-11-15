package org.example

import cats.effect.IO
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, Monad}

import higherkindness.droste._
import org.tessellation.kernel._
import org.tessellation.kernel.Cell.NullTerminal
import org.typelevel.log4cats.slf4j.Slf4jLogger

case class L0TokenTransaction() // TODO: properties needed
case class L0TokenBlock(transactions: Set[L0TokenTransaction]) extends Ω

class L0TokenCell(data: Ω, hypergraphContext: HypergraphContext[IO])
    extends Cell[IO, StackF, Ω, Either[CellError, Ω], Ω](
      data = data,
      hylo = scheme.hyloM(
        AlgebraM[IO, StackF, Either[CellError, Ω]] {
          case More(a)      => a.pure[IO]
          case Done(result) => result.pure[IO]
        },
        CoalgebraM[IO, StackF, Ω] { input =>
          val logger = Slf4jLogger.getLogger[IO]

          input match {
            case ProcessSnapshot(snapshot) =>
              logger.info(s"ProcessSnapshot executed") >>
                logger.info(s"Update balances") >> // TODO: update balances here
                Done(NullTerminal.asRight[CellError]).pure[IO]

            case _ => IO.pure { Done(CellError("Unhandled coalgebra case").asLeft[Ω]) }
          }
        }
      ),
      convert = a => a
    ) {}
