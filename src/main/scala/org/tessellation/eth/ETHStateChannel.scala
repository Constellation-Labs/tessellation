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
import org.tessellation.consensus.{L1Block, L1Transaction}
import org.tessellation.eth.hylo.{ETHEmissionEnd, ReceivedETHBlock, ReceivedETHEmission}
import org.tessellation.eth.schema.{ETHBlock, ETHEmission}
import org.tessellation.eth.web3j.ETHBlockchainClient
import org.tessellation.schema.{CellError, DAG, ETH, LiquidityPool, Ω}
import org.web3j.protocol.core.methods.response.Transaction

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
    in.map(cmd => ETHCell(cmd, ETHContext(liquidityPool, blockchainClient)))
      .evalMap(_.run())
      .flatMap {
        case Left(error)           => Stream.eval(logger.error(error)("ETHCell failed!")) >> Stream.raiseError[IO](error)
        case Right(block: L1Block) => Stream.eval(logger.debug(s"ETHCell produced block: $block")).as(block)
        case Right(eth: ETHEmissionEnd[Ω]) =>
          Stream.eval(logger.debug(s"Emission succeeded: ${eth.hash}")).as(L1Block(Set.empty[L1Transaction]))
        case _ => {
          val err = CellError("Invalid Ω type")
          Stream.eval(logger.error(err)("ETHCell failed!")) >> Stream.raiseError[IO](err)
        }
      }
  private val emissionInput: Stream[IO, ReceivedETHEmission[Ω]] =
    emissionQueue.dequeue
      .map(emission => ReceivedETHEmission[Ω](emission))
      .evalTap(x => logger.debug(x.toString))
  private val blocksInput: Stream[IO, ReceivedETHBlock[Ω]] = blockchainClient.blocks.map { block =>
    val blockNumber = block.getBlock.getNumber
    val blockTxs = block.getBlock.getTransactions.asScala.toList.toSet.asInstanceOf[Set[Transaction]]
    val lpTxs = blockTxs.filter(b => b.getTo != null && b.getTo.equalsIgnoreCase(liquidityPool.xAddress.toLowerCase))

    ETHBlock(blockNumber, lpTxs)
  }.filter(_.transactions.nonEmpty)
    .map(block => ReceivedETHBlock[Ω](block))
    .evalTap(x => logger.debug(x.toString))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "swap" / "eth" / "dag" =>
      implicit val decoder: EntityDecoder[IO, ETHEmission] = jsonOf[IO, ETHEmission]
      for {
        ethEmission <- req.as[ETHEmission]
        _ <- emissionQueue.enqueue1(ethEmission)
        res <- Ok(ethEmission.asJson)
      } yield res
  }
}

object ETHStateChannel {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)

  def apply(
    emissionQueue: Queue[IO, ETHEmission],
    liquidityPool: LiquidityPool[ETH, DAG],
    blockchainClient: ETHBlockchainClient
  ): ETHStateChannel = new ETHStateChannel(emissionQueue, liquidityPool, blockchainClient)

  def init(blockchainUrl: String, ethereumLiquidityPoolAddress: String): Stream[IO, ETHStateChannel] =
    Stream.eval {
      for {
        emissionQueue <- Queue.unbounded[IO, ETHEmission]
        liquidityPool <- LiquidityPool.init[ETH, DAG](
          1, // TODO: It should be provided by liquidity provider
          100, // TODO: It should be provided by liquidity provider
          ethereumLiquidityPoolAddress,
          "DAGLIQUIDITYPOOLADDRESS" // TODO: We do not support DAG -> ETH yet so just a placeholder
        )
        blockchainClient = ETHBlockchainClient(blockchainUrl)
        stateChannel = new ETHStateChannel(emissionQueue, liquidityPool, blockchainClient)
      } yield stateChannel
    }
}
