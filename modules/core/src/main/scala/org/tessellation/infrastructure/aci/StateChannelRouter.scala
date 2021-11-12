package org.tessellation.infrastructure.aci

import cats.data.OptionT
import cats.effect.Async
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._

import org.tessellation.domain.aci.{StateChannelContext, StateChannelInput, StateChannelRouter}
import org.tessellation.kernel.Ω
import org.tessellation.schema.address.Address

import org.typelevel.log4cats.slf4j.Slf4jLogger

object StateChannelRouter {

  def make[F[_]: Async]: F[StateChannelRouter[F]] =
    new StateChannelContextLoader[F].loadFromClasspath.map(make(_))

  def make[F[_]: Async](
    stateChannelContexts: Map[Address, StateChannelContext[F]]
  ): StateChannelRouter[F] = new StateChannelRouter[F] {
    private val logger = Slf4jLogger.getLogger[F]

    def routeInput(input: StateChannelInput): OptionT[F, Ω] =
      for {
        context <- stateChannelContexts.get(input.address).toOptionT[F]
        result <- OptionT.liftF(runCell(input, context))
      } yield result

    private def runCell(input: StateChannelInput, context: StateChannelContext[F]): F[Ω] =
      for {
        cell <- context.createCell(input.bytes)
        _ <- logger.debug(s"Running cell for a state channel with address ${context.address}")
        outputOrError <- cell.run()
        output <- outputOrError.liftTo[F]
      } yield output
  }

}
