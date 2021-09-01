package org.tessellation.eth.web3j

import cats.effect.{ContextShift, IO, Timer}
import fs2._
import fs2.interop.reactivestreams._
import monix.catnap.FutureLift
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.methods.response.{EthBlock, EthSendTransaction}
import org.web3j.protocol.http.HttpService

/**
  * For testing purposes use here infura.io/alchemy.com testnet gateways
  */
class ETHBlockchainClient(blockchainUrl: String) {
  private implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.global)
  private implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)

  private val client = Web3j.build(new HttpService(blockchainUrl))

  // TODO: Switch from FutureLift to Async[F].fromCompletableFuture (cats-effect 3) + remove monix dependency
  def sendTransaction(signedHexTransaction: String): IO[EthSendTransaction] = FutureLift.from {
    IO(client.ethSendRawTransaction(signedHexTransaction).sendAsync())
  }

  def blocks: Stream[IO, EthBlock] =
    client
      .blockFlowable(true)
      .toStream[IO]
}

object ETHBlockchainClient {
  def apply(blockchainUrl: String): ETHBlockchainClient = new ETHBlockchainClient(blockchainUrl)
}
