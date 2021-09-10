package org.tessellation.eth.web3j

import cats.effect.IO
import monix.catnap.FutureLift
import org.tessellation.eth.utils.Convert.asciiToHex
import org.web3j.crypto.{Credentials, RawTransaction}
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.http.HttpService
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.utils.Convert

import java.math.BigInteger

/**
  * Just for testing purposes!
  */
class ETHTransactionGenerator(
  blockchainUrl: String,
  liquidityPoolAddress: String,
  privateKey: String
) {
  private val credentials = Credentials.create(privateKey)
  private val client: Web3j = Web3j.build(new HttpService(blockchainUrl))
  private val rawTransactionManager: RawTransactionManager = new RawTransactionManager(client, credentials)

  def createSampleRawTransaction(destinationDAGAddress: String): IO[String] =
    for {
      nonce <- getNonce
      value = Convert.toWei("0.0001", Convert.Unit.ETHER).toBigInteger
      to = liquidityPoolAddress
      gasPrice = DefaultGasProvider.GAS_PRICE
      gasLimit = DefaultGasProvider.GAS_LIMIT
      data = asciiToHex(destinationDAGAddress)
      tx = RawTransaction
        .createTransaction(nonce, gasPrice, gasLimit, to, value, data)
      signedTx = rawTransactionManager.sign(tx)
    } yield signedTx

  private def getNonce: IO[BigInteger] =
    FutureLift.from {
      IO(client.ethGetTransactionCount(credentials.getAddress, DefaultBlockParameterName.LATEST).sendAsync())
    }.map(_.getTransactionCount)
}

object ETHTransactionGenerator {

  def apply(blockchainUrl: String, liquidityPoolAddress: String, privateKey: String): ETHTransactionGenerator =
    new ETHTransactionGenerator(blockchainUrl, liquidityPoolAddress, privateKey)
}
