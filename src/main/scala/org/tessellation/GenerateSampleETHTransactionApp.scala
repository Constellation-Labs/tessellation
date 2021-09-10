package org.tessellation

import cats.effect.{ExitCode, IO, IOApp}
import org.tessellation.config.Config
import org.tessellation.eth.web3j.ETHTransactionGenerator

object GenerateSampleETHTransactionApp extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    for {
      config <- Config.load()
      generator = ETHTransactionGenerator(
        config.ethereumBlockchainUrl,
        config.ethereumLiquidityPoolAddress,
        config.ethereumGeneratorPrivateKey
      )
      txHash <- generator.createSampleRawTransaction("DAGSampleDestinationAddress")
      _ <- IO {
        println(txHash)
      }
    } yield ExitCode.Success
}
