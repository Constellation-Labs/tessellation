package org.example

import cats.Applicative
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.show._
import eu.timepit.refined.auto._
import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import io.constellationnetwork.kernel._
import io.constellationnetwork.schema.address.Address
import org.typelevel.log4cats.slf4j.Slf4jLogger

object SimpleSnapshotPublisherDef extends StateChannelDef[EmitSimpleSnapshot, Ω, EmitSimpleSnapshot] {

  val address: Address = Address("DAG3k3VihUWMjse9LE93jRqZLEuwGd6a5Ypk4zYS")

  val kryoRegistrar = Map(
    classOf[EmitSimpleSnapshot] -> 1000,
    classOf[SimpleSnapshot] -> 1001
  )

  def makeCell[F[_]: Async](input: EmitSimpleSnapshot, hgContext: HypergraphContext[F]) =
    new Cell[F, StackF, EmitSimpleSnapshot, Either[CellError, Ω], EmitSimpleSnapshot](
      data = input,
      hylo = scheme.hyloM(
        AlgebraM[F, StackF, Either[CellError, Ω]] {
          case More(a)      => a.pure[F]
          case Done(result) => result.pure[F]
        },
        CoalgebraM[F, StackF, Ω] { input =>
          val logger = Slf4jLogger.getLogger[F]

          input match {
            case EmitSimpleSnapshot(lastSnapshotHash) =>
              logger.info(s"Emitting snapshot with lsh ${lastSnapshotHash.show}") >>
                Applicative[F].pure(Done(SimpleSnapshot(lastSnapshotHash).asRight[CellError]))

            case _ => Applicative[F].pure(Done(CellError("Unhandled coalgebra case").asLeft[Ω]))
          }
        }
      ),
      convert = a => a
    )
}
