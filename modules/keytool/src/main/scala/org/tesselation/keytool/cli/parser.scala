package org.tesselation.keytool.cli

import cats.data.EitherT
import cats.effect.kernel.Async

import org.tesselation.keytool.cli.config.{CliConfig, CliMethod}

import scopt._

object parser {

  private val cliParser: OParser[Unit, CliConfig] = {
    val builder: OParserBuilder[CliConfig] = OParser.builder[CliConfig]
    import builder._

    OParser.sequence(
      programName("cl-keytool"),
      help("help"),
      cmd("generate")
        .action((_, c) => c.copy(method = CliMethod.GenerateWallet))
        .text("Generate wallet (KeyStore with KeyPair inside)"),
      cmd("migrate")
        .action((_, c) => c.copy(method = CliMethod.MigrateExistingKeyStoreToStorePassOnly))
        .text("Clone existing KeyStore and set storepass for both store and keypair"),
      cmd("export")
        .action((_, c) => c.copy(method = CliMethod.ExportPrivateKeyHex))
        .text("Exports PrivateKey in hexadecimal format")
    )
  }

  def parse[F[_]: Async](args: List[String]): F[CliConfig] = {
    val argsHelp = if (args.isEmpty) {
      List("--help")
    } else args

    val setup: OParserSetup = new DefaultOParserSetup {
      override def showUsageOnError = Some(true)
    }

    EitherT
      .fromEither[F] {
        OParser.parse(cliParser, argsHelp, CliConfig(), setup).toRight(new RuntimeException("CLI params are missing"))
      }
      .rethrowT
  }

}
