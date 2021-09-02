package org.tessellation.aci.endpoint

import cats.data.OptionT
import cats.effect._
import cats.implicits._
import fs2.concurrent.Queue
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.chrisdavenport.mapref.MapRef
import fs2._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.Http4sDsl
import org.http4s.{EntityDecoder, _}
import org.tessellation.aci.StateChannelRuntime
import org.tessellation.consensus.L1Block
import org.tessellation.schema.Cell.NullTerminal
import org.tessellation.schema.CellError

class StateChannelHandler[F[_]: RaiseThrowable](
  inputQueue: Queue[F, (Array[Byte], StateChannelRuntime)],
  runtimeCache: MapRef[F, String, Option[StateChannelRuntime]]
)(implicit F: ConcurrentEffect[F], C: ContextShift[F])
    extends Http4sDsl[F] {

  private val logger = Slf4jLogger.getLogger[F]

  implicit val decoder: EntityDecoder[F, Array[Byte]] = EntityDecoder.byteArrayDecoder[F]
  implicit val encoder: EntityEncoder[F, Array[Byte]] = EntityEncoder.byteArrayEncoder[F]

  def routes: HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req @ POST -> Root / "input" / address =>
        for {
          _ <- logger.info(s"Received input for address $address")
          payload <- req.as[Array[Byte]]
          result <- OptionT(runtimeCache(address).get).semiflatMap { runtime =>
            inputQueue.enqueue1((payload, runtime)) >> Ok(runtime.toString)
          }.getOrElseF(NotFound(s"State Channel not found for address $address"))
        } yield result
    }

  val l1Input: Stream[F, (Array[Byte], StateChannelRuntime)] = inputQueue.dequeue

  val l1: Pipe[F, (Array[Byte], StateChannelRuntime), L1Block] =
    (in: Stream[F, (Array[Byte], StateChannelRuntime)]) =>
      in.evalTap { case (_, runtime) => logger.info(s"Handling input for address ${runtime.address}") }.evalMap {
        case (inputBytes, runtime) =>
          for {
            input <- runtime.deserializeInput(inputBytes)
            cell = runtime.createCell[F](input)
          } yield cell
      }.evalMap(_.run()).flatMap {
        case Left(error)            => Stream.raiseError(error)
        case Right(block: L1Block)  => Stream.eval(logger.info(s"Output block $block")).as(block)
        case Right(_: NullTerminal) => Stream.eval(logger.info(s"Terminal object")) >> Stream.empty
        case e @ _                  => Stream.raiseError(CellError(s"Invalid cell output type $e"))
      }

}

object StateChannelHandler {

  def init[F[_]: ConcurrentEffect: ContextShift](
    runtimeCache: Map[String, StateChannelRuntime]
  ): Stream[F, StateChannelHandler[F]] =
    Stream.eval {
      for {
        inputQueue <- Queue.unbounded[F, (Array[Byte], StateChannelRuntime)]
        runtimeCache <- MapRef.ofSingleImmutableMap(runtimeCache)
      } yield new StateChannelHandler[F](inputQueue, runtimeCache)
    }
}
