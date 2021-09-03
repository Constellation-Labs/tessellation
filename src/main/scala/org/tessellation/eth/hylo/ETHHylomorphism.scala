package org.tessellation.eth.hylo

import cats.effect.{IO, Timer}
import cats.implicits._
import higherkindness.droste.{AlgebraM, CoalgebraM}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.tessellation.consensus.{L1Block, L1Transaction}
import org.tessellation.eth.schema.ETHBlock
import org.tessellation.eth.utils.Convert.hexToAscii
import org.tessellation.eth.web3j.{ETHBlockchainClient, ETHTransactionGenerator}
import org.tessellation.schema.{CellError, Ω}
import org.web3j.protocol.core.methods.response.EthBlock.TransactionObject
import org.web3j.protocol.core.methods.response.Transaction
import org.web3j.utils.Convert

import java.math.BigDecimal

object ETHHylomorphism {
  implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.global)
  // TODO: Pass via context?
  val testnetUrl = ""
  // TODO: Pass via context?
  val client = ETHBlockchainClient(testnetUrl)

  val algebra: AlgebraM[IO, ETHConsensusF, Either[CellError, Ω]] = AlgebraM {
    case result @ ETHSwapEnd(_) =>
      IO {
        result.block.asRight[CellError]
      }
    case ETHEmissionError() =>
      IO {
        CellError("ETH emission failed. Transaction wasn't found").asLeft[Ω]
      }

    case a @ _ =>
      IO {
        a.asRight[CellError]
      }
  }
  private val logger = Slf4jLogger.getLogger[IO]

  val coalgebra: CoalgebraM[IO, ETHConsensusF, Ω] = CoalgebraM {
    case ReceivedETHEmission(emission) =>
      for {
        tx <- sendETHTransactionToETHChain(emission.ethTransactionHex)
        next = tx match {
          case Some(value) => ETHEmissionEnd[Ω](value)
          case None        => ETHEmissionError[Ω]()
        }
      } yield next

    case ReceivedETHBlock(block) =>
      for {
        _ <- updateLiquidityPoolLedger(block.transactions) // TODO: Update liquidity pool!
        l1Block <- createDAGTransactionsForL0(block)
      } yield ETHSwapEnd(l1Block)
  }

  def sendETHTransactionToETHChain(signedHexTransaction: String): IO[Option[Transaction]] =
    for {
      _ <- logger.debug(s"Sending raw ETH transaction with hex: $signedHexTransaction")
      hash <- client.sendTransaction(signedHexTransaction)
      _ <- logger.debug(s"Obtained transaction hash: $hash")
      tx <- client.getByHash(hash)
      weiValue = tx.map(_.getValue).map(new BigDecimal(_))
      ethValue = weiValue.map(wei => Convert.fromWei(wei, Convert.Unit.ETHER))
      input = tx.map(_.getInput).map(hexToAscii)
      _ <- logger.debug(s"Transaction value: $weiValue WEI ($ethValue ETH)")
      _ <- logger.debug(s"Transaction input: $input")
    } yield tx

  // TODO: Unmock
  def waitForCorrespondingETHBlock(): IO[ETHBlock] = IO.sleep(10.seconds) >> IO(ETHBlock())

  // TODO: Unmock
  def updateLiquidityPoolLedger(transactions: Set[TransactionObject]): IO[Unit] = IO.unit

  // TODO: Unmock
  def createDAGTransactionsForL0(block: ETHBlock): IO[L1Block] = {
    val ethTransactions = block.transactions.toList
    val dagTransactions = ethTransactions.map(t => L1Transaction(t.getValue.intValue(), "A", "B"))
    IO(L1Block(dagTransactions.toSet))
  }
}
