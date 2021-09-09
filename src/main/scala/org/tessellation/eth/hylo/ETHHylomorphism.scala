package org.tessellation.eth.hylo

import cats.data.EitherT
import cats.effect.{IO, Timer}
import cats.implicits._
import higherkindness.droste.{AlgebraM, CoalgebraM}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.tessellation.consensus.{L1Block, L1Transaction}
import org.tessellation.eth.ETHCellInput
import org.tessellation.eth.hylo.ETHHylomorphism.swapTransaction
import org.tessellation.eth.schema.ETHBlock
import org.tessellation.eth.utils.Convert.hexToAscii
import org.tessellation.eth.web3j.{ETHBlockchainClient, ETHTransactionGenerator}
import org.tessellation.schema.{CellError, DAG, ETH, LiquidityPool, Ω}
import org.web3j.protocol.core.methods.response.EthBlock.TransactionObject
import org.web3j.protocol.core.methods.response.Transaction
import org.web3j.utils.Convert

import java.math.BigDecimal
import scala.util.Try

sealed trait ETHHylomorphismError extends Throwable

case class DAGAddressMissing(tx: Transaction) extends ETHHylomorphismError

case class DAGAddressIncorrect(tx: Transaction) extends ETHHylomorphismError

case class ETHValueIncorrect(tx: Transaction) extends ETHHylomorphismError

case class SwapFailed(tx: Transaction) extends ETHHylomorphismError

case class SendETHTransactionError(txHex: String) extends ETHHylomorphismError

case class GetETHTransactionByHashError(txHex: String) extends ETHHylomorphismError

object ETHHylomorphism {

  val algebra: AlgebraM[IO, ETHConsensusF, Either[CellError, Ω]] = AlgebraM {
    case result @ ETHSwapEnd(_) =>
      IO {
        result.block.asRight[CellError]
      }
    case ETHEmissionError(block) =>
      IO {
        CellError(s"ETH emission failed for block: $block").asLeft[Ω]
      }
    case result @ ETHEmissionEnd(hash) =>
      IO {
        result.asRight[CellError]
      }

    case a @ _ =>
      IO {
        a.asRight[CellError]
      }
  }

  val coalgebra: CoalgebraM[IO, ETHConsensusF, Ω] = CoalgebraM {
    case ETHCellInput(cmd, ctx) =>
      cmd match {
        case ReceivedETHEmission(emission) =>
          sendETHTransaction(emission.ethTransactionHex, ctx.ethBlockchainClient).value.map {
            case Left(_)      => ETHEmissionError[Ω](emission.ethTransactionHex)
            case Right(value) => ETHEmissionEnd[Ω](value)
          }

        case ReceivedETHBlock(block) =>
          block.transactions.toList
            .traverse(tx => swapTransaction(tx, ctx.lp))
            .map(dagTxs => L1Block(dagTxs.toSet))
            .value
            .map {
              case Left(_)      => ETHSwapError[Ω](block)
              case Right(value) => ETHSwapEnd[Ω](value)
            }
      }
  }
  private val logger = Slf4jLogger.getLogger[IO]

  def swapTransaction(
    tx: Transaction,
    liquidityPool: LiquidityPool[ETH, DAG]
  ): EitherT[IO, ETHHylomorphismError, L1Transaction] =
    for {
      dagAddress <- extractDAGAddress(tx).toEitherT[IO]
      ethOffer <- extractETHValue(tx).toEitherT[IO]
      _ <- EitherT.liftF(logger.debug(s"[${tx.getHash}] Swapping $ethOffer ETH for DAG address $dagAddress"))
      dagTransaction <- liquidityPool
        .swap(ethOffer) { dagOffer =>
          L1Transaction((dagOffer / 1e8).toLong, liquidityPool.yAddress, dagAddress).pure[IO]
        }
        .attemptT
        .leftMap(_ => SwapFailed(tx))
        .leftWiden[ETHHylomorphismError]
    } yield dagTransaction

  def extractDAGAddress(tx: Transaction): Either[ETHHylomorphismError, String] = {
    val input = tx.getInput

    for {
      hex <- Either.cond(input.nonEmpty, input, DAGAddressMissing(tx))
      ascii <- Either
        .fromTry(Try(hexToAscii(hex)))
        .leftMap[ETHHylomorphismError](_ => DAGAddressIncorrect(tx))
      dstAddress <- Either
        .cond(ascii.startsWith("DAG"), ascii, DAGAddressIncorrect(tx))
    } yield dstAddress
  }

  def extractETHValue(tx: Transaction): Either[ETHHylomorphismError, BigDecimal] =
    Either
      .fromTry(Try(Convert.fromWei(new BigDecimal(tx.getValue), Convert.Unit.ETHER)))
      .leftMap(_ => ETHValueIncorrect(tx))

  def sendETHTransaction(
    signedHexTransaction: String,
    client: ETHBlockchainClient
  ): EitherT[IO, ETHHylomorphismError, String] =
    for {
      _ <- EitherT.liftF(logger.debug(s"Sending raw transaction with hex: $signedHexTransaction"))
      hash <- client
        .sendTransaction(signedHexTransaction)
        .attemptT
        .leftMap[ETHHylomorphismError](_ => SendETHTransactionError(signedHexTransaction))

      _ <- EitherT.liftF(logger.debug(s"[$hash] Getting transaction from chain"))

      _ <- EitherT.fromOptionF[IO, ETHHylomorphismError, Transaction](
        client.getByHash(hash),
        GetETHTransactionByHashError(hash)
      )
    } yield hash
}
