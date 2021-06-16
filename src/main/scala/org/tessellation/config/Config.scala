package org.tessellation.config

import cats.effect.IO
import pureconfig._
import pureconfig.generic.auto._

case class Config(
  startOwnConsensusRounds: Boolean,
  ip: String,
  generatorSrc: String
)

object Config {

  def load(): IO[Config] =
    IO {
      ConfigSource.default
        .load[Config]
        .getOrElse(
          Config(
            startOwnConsensusRounds = false,
            ip = "",
            generatorSrc = ""
          )
        )
    }
}
