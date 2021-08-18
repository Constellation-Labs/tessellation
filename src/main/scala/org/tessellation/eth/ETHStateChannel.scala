package org.tessellation.eth

import cats.effect.{ContextShift, IO, Timer}
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.tessellation.consensus.L1Block
import org.tessellation.schema.{CellError, Ω}
import fs2._
import _root_.io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.tessellation.eth.hylo.{ReceivedETHBlock, ReceivedETHEmission}
import org.tessellation.eth.schema.{ETHBlock, ETHEmission, NativeETHTransaction}

class ETHStateChannel {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.global)
  private val logger = Slf4jLogger.getLogger[IO]

  // It emits ETH blocks periodically pulled from ETH chain
  val periodicalyFetchBlocks: Stream[IO, ReceivedETHBlock[Ω]] =
    Stream(ETHBlock(Set(NativeETHTransaction.getRandom())))
      .evalMap(block => IO.sleep(5.seconds).as(block))
      .map(block => ReceivedETHBlock[Ω](block))
      .repeat

  // It emits emission requests sent to HTTP API of Node
  val ethEmissionFromHTTPAPI: Stream[IO, ReceivedETHEmission[Ω]] =
    Stream(ETHEmission(NativeETHTransaction.getRandom(), "DAGSampleAddress"))
      .evalMap(emission => IO.sleep(4.seconds).as(emission))
      .map(emission => ReceivedETHEmission[Ω](emission))
      .repeat

  val l1Input: Stream[IO, Ω] = periodicalyFetchBlocks.merge(ethEmissionFromHTTPAPI)

  // Whole pipeline is merged and emits all the possible input datatypes for ETHCell
  val L1: Pipe[IO, Ω, L1Block] = (in: Stream[IO, Ω]) =>
    in.map(ETHCell)
      .evalMap(_.run())
      .map {
        case Left(error)           => Left(error)
        case Right(block: L1Block) => Right(block)
        case _                     => Left(CellError("Invalid Ω type"))
      }
      .map(_.right.get) // TODO: Get rid of get

}
