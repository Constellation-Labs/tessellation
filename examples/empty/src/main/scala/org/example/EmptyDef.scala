package org.example

import cats.syntax.either._
import cats.syntax.functor._
import cats.{Applicative, Monad}
import eu.timepit.refined.auto._
import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import org.tessellation.kernel.Cell.NullTerminal
import org.tessellation.kernel._
import org.tessellation.schema.address.Address

object EmptyDef extends StateChannelDef[EmptyInput, Ω, EmptyInput] {

  val address: Address = Address("DAG3k3VihUWMjse9LE93jRqZLEuwGd6a5Ypk4zYS")
  val kryoRegistrar: Map[Class[_], KryoRegistrationId] = Map(classOf[EmptyInput] -> 1001)

  def makeCell[F[_]: Monad](input: EmptyInput, hgContext: HypergraphContext[F]) =
    new Cell[F, StackF, EmptyInput, Either[CellError, Ω], EmptyInput](
      data = input,
      hylo = scheme.hyloM(
        AlgebraM[F, StackF, Either[CellError, Ω]] { _ =>
          Monad[F].pure(NullTerminal.asRight[CellError].widen[Ω])
        },
        CoalgebraM[F, StackF, Ω] { _ =>
          Applicative[F].compose[StackF].pure(EmptyInput().asInstanceOf[Ω])
        }
      ),
      convert = a => a
    )
}
