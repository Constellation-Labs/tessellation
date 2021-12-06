package org.tessellation.infrastructure.aci

import cats.data.OptionT
import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._

import org.tessellation.domain.aci._
import org.tessellation.kernel.{HypergraphContext, StateChannelContext, Ω}
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance

import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.slf4j.Slf4jLogger

object StateChannelRouter {

  def make[F[_]: Async](stateChannelOutputQueue: Queue[F, StateChannelOutput]): F[StateChannelRouter[F]] =
    for {
      instances <- new StateChannelInstanceLoader[F].loadFromClasspath
      contexts <- instances.toList.traverse {
        case (stateChannelAddress, _) =>
          MapRef.ofConcurrentHashMap[F, Address, Balance]().map { balances =>
            stateChannelAddress ->
              new StateChannelContext[F] {
                val address: Address = stateChannelAddress
                def getBalance(address: Address): F[Balance] =
                  balances(address).get.map(_.getOrElse(Balance.empty))
                def setBalance(address: Address, balance: Balance): F[Unit] = balances(address).set(balance.some)
              }
          }
      }.map(_.toMap)
    } yield make(instances, contexts, stateChannelOutputQueue)

  def make[F[_]: Async](
    stateChannelInstances: Map[Address, StateChannelInstance[F]],
    stateChannelContexts: Map[Address, StateChannelContext[F]],
    stateChannelOutputQueue: Queue[F, StateChannelOutput]
  ): StateChannelRouter[F] = new StateChannelRouter[F] {
    private val logger = Slf4jLogger.getLogger[F]

    val hypergraphContext = new HypergraphContext[F] {

      def getStateChannelContext(address: Address): F[Option[StateChannelContext[F]]] =
        stateChannelContexts.get(address).pure[F]
    }

    def routeInput(input: StateChannelInput): OptionT[F, Ω] =
      for {
        instance <- stateChannelInstances.get(input.address).toOptionT[F]
        result <- OptionT.liftF(runCell(instance, input))
      } yield result

    private def runCell(instance: StateChannelInstance[F], input: StateChannelInput): F[Ω] =
      for {
        _input <- instance.kryoSerializer.deserialize[Ω](input.bytes).liftTo[F]
        cell = instance.makeCell(_input, hypergraphContext)
        _ <- logger.debug(s"Running cell for a state channel with address ${instance.address}")
        outputOrError <- cell.run()
        output <- outputOrError.liftTo[F]
        outputBytes <- instance.kryoSerializer.serialize(output).liftTo[F]
        _ <- stateChannelOutputQueue.offer(StateChannelOutput(instance.address, output, outputBytes))
      } yield output

  }

}
