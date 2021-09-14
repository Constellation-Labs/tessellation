package org.tesselation.keytool.cli

import org.tesselation.keytool.cli.config.CliMethod.CliMethod

object config {

  object CliMethod extends Enumeration {
    type CliMethod = Value

    val GenerateWallet, MigrateExistingKeyStoreToStorePassOnly, ExportPrivateKeyHex = Value
  }

  case class CliConfig(method: CliMethod = null)

}
