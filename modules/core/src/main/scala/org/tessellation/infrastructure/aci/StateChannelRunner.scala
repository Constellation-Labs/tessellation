package org.tessellation.infrastructure.aci

import cats.data.OptionT
import cats.effect.std.Queue
import cats.effect.{Async, Spawn}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._

import scala.util.control.NoStackTrace

import org.tessellation.domain.aci._
import org.tessellation.kernel.{HypergraphContext, StateChannelContext, Ω}
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance

import fs2.Stream
import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.slf4j.Slf4jLogger

object StateChannelRunner {

  case class StateChannelDoesNotExist(address: Address) extends NoStackTrace

  def makeStateChannelContext[F[_]: Async](stateChannelAddress: Address): F[(Address, StateChannelContext[F])] =
    Queue.unbounded[F, Ω].flatMap { inputQueue =>
      MapRef.ofConcurrentHashMap[F, Address, Balance]().map { balances =>
        stateChannelAddress ->
          new StateChannelContext[F] {
            val address: Address = stateChannelAddress

            val inputs: Stream[F, Ω] = Stream.fromQueueUnterminated(inputQueue)
            def enqueueInput(input: Ω) = inputQueue.offer(input)

            def getBalance(address: Address): F[Balance] =
              balances(address).get.map(_.getOrElse(Balance.empty))
            def setBalance(address: Address, balance: Balance): F[Unit] = balances(address).set(balance.some)
          }
      }
    }

  def make[F[_]: Async](stateChannelOutputQueue: Queue[F, StateChannelOutput]): F[StateChannelRunner[F]] =
    for {
      instances <- new StateChannelInstanceLoader[F].loadFromClasspath
      contexts <- instances.keySet.toList.traverse {
        makeStateChannelContext[F]
      }.map(_.toMap)
    } yield make(instances, contexts, stateChannelOutputQueue)

  def make[F[_]: Async](
    stateChannelInstances: Map[Address, StateChannelInstance[F]],
    stateChannelContexts: Map[Address, StateChannelContext[F]],
    stateChannelOutputQueue: Queue[F, StateChannelOutput]
  ): StateChannelRunner[F] = new StateChannelRunner[F] {
    val logger = Slf4jLogger.getLogger[F]

    val hypergraphContext = new HypergraphContext[F] {

      def getStateChannelContext(address: Address): F[Option[StateChannelContext[F]]] =
        stateChannelContexts.get(address).pure[F]
    }

    def initializeKnownCells: F[Unit] =
      stateChannelContexts.keySet.toList
        .traverse(initializeCell(_).handleErrorWith {
          case StateChannelDoesNotExist(address) =>
            logger.error(s"Trying to initialize state channel for address=${address.show}, but it does not exist!")
          case err => logger.error(err)("Unknown error")
        })
        .void

    private def getContextAndInstance(address: Address): Option[(StateChannelContext[F], StateChannelInstance[F])] =
      stateChannelContexts.get(address).flatMap { context =>
        stateChannelInstances.get(address).map { instance =>
          (context, instance)
        }
      }

    def initializeCell(address: Address): F[Unit] =
      getContextAndInstance(address).map {
        case (context, instance) =>
          def runCell(input: Ω): F[Ω] =
            instance
              .makeCell(input, hypergraphContext)
              .run()
              .flatMap(_.liftTo[F])

          def publishOutput(output: Ω): F[Unit] =
            instance.kryoSerializer
              .serialize(output)
              .liftTo[F]
              .flatMap { outputBytes =>
                val scOutput = StateChannelOutput(instance.address, output, outputBytes)
                stateChannelOutputQueue.offer(scOutput)
              }

          val pipeline = context.inputs
            .through(instance.inputPipe)
            .evalMap(runCell)
            .through(instance.outputPipe)
            .evalTap(publishOutput)
            .compile
            .drain

          Spawn[F].start(pipeline).void
      }.getOrElse(StateChannelDoesNotExist(address).raiseError[F, Unit])

    def routeInput(input: StateChannelInput): OptionT[F, Unit] =
      for {
        instance <- stateChannelInstances.get(input.address).toOptionT[F]
        context <- stateChannelContexts.get(input.address).toOptionT[F]

        _input <- instance.kryoSerializer.deserialize[Ω](input.bytes).toOption.toOptionT[F]
        _ <- OptionT.liftF(context.enqueueInput(_input))
      } yield ()
  }

}
