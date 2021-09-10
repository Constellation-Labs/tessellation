package org.tessellation.config

import cats.effect.IO
import pureconfig._
import pureconfig.generic.auto._

case class Config(
  startOwnConsensusRounds: Boolean,
  ip: String,
  generatorSrc: String,
  ethereumBlockchainUrl: String, // TODO: Each state channel should have separated config so it shouldn't be there!
  ethereumLiquidityPoolAddress: String,
  ethereumGeneratorPrivateKey: String
)

object Config {

  def load(): IO[Config] =
    IO {
      ConfigSource.default
        .load[Config]
        .getOrElse(
          Config(
            startOwnConsensusRounds = false,
            ip = "0.0.0.0",
            generatorSrc = "",
            ethereumBlockchainUrl = "",
            ethereumLiquidityPoolAddress = "",
            ethereumGeneratorPrivateKey = ""
          )
        )
    }
}
