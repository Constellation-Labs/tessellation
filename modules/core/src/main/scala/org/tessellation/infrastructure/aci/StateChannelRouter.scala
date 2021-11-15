package org.tessellation.infrastructure.aci

import cats.data.OptionT
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._

import org.tessellation.domain.aci.{StateChannelInput, StateChannelRouter, StdCell}
import org.tessellation.kernel.{HypergraphContext, StateChannelContext, Ω}
import org.tessellation.schema.address.Address

import org.typelevel.log4cats.slf4j.Slf4jLogger

object StateChannelRouter {

  def make[F[_]: Async]: F[StateChannelRouter[F]] =
    new StateChannelContextLoader[F].loadFromClasspath.map(make(_))

  def make[F[_]: Async](
    stateChannelContexts: Map[Address, StateChannelContext[F]]
  ): StateChannelRouter[F] = new StateChannelRouter[F] {
    private val logger = Slf4jLogger.getLogger[F]

    val hypergraphContext = new HypergraphContext[F] {

      def getStateChannelContext(address: Address): F[Option[StateChannelContext[F]]] =
        stateChannelContexts.get(address).pure[F]

      def createCell(stateChannel: Address)(input: Array[Byte]): F[Option[StdCell[F]]] =
        getStateChannelContext(stateChannel).flatMap(_.map(_.createCell(input, this)).sequence)
    }

    def routeInput(input: StateChannelInput): OptionT[F, Ω] =
      for {
        context <- stateChannelContexts.get(input.address).toOptionT[F]
        result <- OptionT.liftF(runCell(input, context))
      } yield result

    private def runCell(input: StateChannelInput, context: StateChannelContext[F]): F[Ω] =
      for {
        cell <- context.createCell(input.bytes, hypergraphContext)
        _ <- logger.debug(s"Running cell for a state channel with address ${context.address}")
        outputOrError <- cell.run()
        output <- outputOrError.liftTo[F]
      } yield output
  }

}
