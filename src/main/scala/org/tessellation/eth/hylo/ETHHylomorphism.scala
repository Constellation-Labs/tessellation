package org.tessellation.eth.hylo

import cats.effect.{IO, Timer}
import cats.implicits._
import higherkindness.droste.{AlgebraM, CoalgebraM}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.tessellation.consensus.{L1Block, L1Transaction}
import org.tessellation.eth.schema.ETHBlock
import org.tessellation.schema.{CellError, Ω}
import org.web3j.protocol.core.methods.response.EthBlock.TransactionObject

object ETHHylomorphism {
  implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.global)

  val algebra: AlgebraM[IO, ETHConsensusF, Either[CellError, Ω]] = AlgebraM {
    case result @ ETHSwapEnd(_) =>
      IO {
        result.block.asRight[CellError]
      }
    case WaitForCorrespondingBlockTimeout() =>
      IO {
        CellError("Block corresponding to emitted ETH transaction wasn't found in a reasonable time").asLeft[Ω]
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
        _ <- sendETHTransactionToETHChain(emission.ethTransactionHex)
        blockOrTimeout <- waitForCorrespondingETHBlock()
          .map(ReceivedETHBlock[Ω])
          .handleErrorWith(_ => IO(WaitForCorrespondingBlockTimeout[Ω]()))
      } yield blockOrTimeout

    case ReceivedETHBlock(block) =>
      for {
        _ <- updateLiquidityPoolLedger(block.transactions)
        l1Block <- createDAGTransactionsForL0(block)
      } yield ETHSwapEnd(l1Block)
  }

  // TODO: Unmock
  def sendETHTransactionToETHChain(ethTransactionHex: String): IO[Unit] = IO.unit

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
