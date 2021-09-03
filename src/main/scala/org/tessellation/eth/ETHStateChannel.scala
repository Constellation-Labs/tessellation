package org.tessellation.eth

import cats.effect.{ContextShift, IO, Timer}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.web3j.protocol.core.methods.response.EthBlock.TransactionObject
import io.circe.syntax._
import org.http4s.circe._
import org.http4s.dsl.io._
import fs2._
import fs2.concurrent.Queue
import org.http4s.{EntityDecoder, HttpRoutes}
import org.tessellation.consensus.L1Block
import org.tessellation.eth.hylo.{ReceivedETHBlock, ReceivedETHEmission}
import org.tessellation.eth.schema.{ETHBlock, ETHEmission}
import org.tessellation.eth.web3j.ETHBlockchainClient
import org.tessellation.schema.{CellError, DAG, ETH, LiquidityPool, Ω}

import scala.jdk.CollectionConverters._

class ETHStateChannel(
  emissionQueue: Queue[IO, ETHEmission],
  liquidityPool: LiquidityPool[ETH, DAG],
  blockchainClient: ETHBlockchainClient
) {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.global)
  private val logger = Slf4jLogger.getLogger[IO]

  val l1Input: Stream[IO, Ω] = blocksInput.merge(emissionInput)

  val l1: Pipe[IO, Ω, L1Block] = (in: Stream[IO, Ω]) =>
    in.map(ETHCell)
      .evalMap(_.run())
      .flatMap {
        case Left(error)           => Stream.eval(logger.error(error)("ETHCell failed!")) >> Stream.raiseError[IO](error)
        case Right(block: L1Block) => Stream.eval(logger.debug(s"ETHCell produced block: $block")).as(block)
        case _ => {
          val err = CellError("Invalid Ω type")
          Stream.eval(logger.error(err)("ETHCell failed!")) >> Stream.raiseError[IO](err)
        }
      }

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "swap" / "eth" / "dag" =>
      implicit val decoder: EntityDecoder[IO, ETHEmission] = jsonOf[IO, ETHEmission]
      for {
        ethEmission <- req.as[ETHEmission]
        _ <- emissionQueue.enqueue1(ethEmission)
        res <- Ok(ethEmission.asJson)
      } yield res
  }

  private val emissionInput: Stream[IO, ReceivedETHEmission[Ω]] =
    emissionQueue.dequeue
      .map(emission => ReceivedETHEmission[Ω](emission))
      .evalTap(x => logger.debug(x.toString))

  private val blocksInput: Stream[IO, ReceivedETHBlock[Ω]] = blockchainClient.blocks.map { block =>
    val lpTxs = block.getBlock.getTransactions.asScala.toList
      .asInstanceOf[List[TransactionObject]]
      .filter(_.getTo == liquidityPool.xAddress) // TODO: Enable to get only blocks affecting ETH lp address
      .toSet
    ETHBlock(lpTxs)
  }.filter(_.transactions.nonEmpty).map(block => ReceivedETHBlock[Ω](block)).evalTap(x => logger.debug(x.toString))
}

object ETHStateChannel {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)

  def apply(
    emissionQueue: Queue[IO, ETHEmission],
    liquidityPool: LiquidityPool[ETH, DAG],
    blockchainClient: ETHBlockchainClient
  ): ETHStateChannel = new ETHStateChannel(emissionQueue, liquidityPool, blockchainClient)

  def init(blockchainUrl: String): Stream[IO, ETHStateChannel] =
    Stream.eval {
      for {
        emissionQueue <- Queue.unbounded[IO, ETHEmission]
        liquidityPool = LiquidityPool[ETH, DAG](
          1,
          100,
          "0xETHLIQUIDITYPOOLADDRESS",
          "DAGLIQUIDITYPOOLADDRESS" // TODO: We do not support DAG -> ETH yet so just a placeholder
        ) // TODO: It should be provided by liquidity provider
        blockchainClient = ETHBlockchainClient(blockchainUrl)
        stateChannel = new ETHStateChannel(emissionQueue, liquidityPool, blockchainClient)
      } yield stateChannel
    }
}
