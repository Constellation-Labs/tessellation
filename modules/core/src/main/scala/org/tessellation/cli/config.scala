package org.tessellation.cli

import org.tessellation.cli.config.CliMethod.CliMethod

import fs2.io.file.Path

object config {

  object CliMethod extends Enumeration {
    type CliMethod = Value

    val RunValidator, RunGenesis = Value
  }

  case class CliConfig(
    method: CliMethod = null,
    genesisPath: Path = null
  )

}
