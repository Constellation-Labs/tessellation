package org.tessellation.cli

import cats.data.EitherT
import cats.effect.Async

import org.tessellation.cli.config.{CliConfig, CliMethod}

import fs2.io.file.Path
import scopt._

object parser {

  private val cliParser: OParser[Unit, CliConfig] = {
    val builder: OParserBuilder[CliConfig] = OParser.builder[CliConfig]
    import builder._

    OParser.sequence(
      programName("cl_node"),
      help("help"),
      cmd("run-validator")
        .action((_, c) => c.copy(method = CliMethod.RunValidator))
        .text("Run validator node"),
      cmd("run-genesis")
        .action((_, c) => c.copy(method = CliMethod.RunGenesis))
        .text("Run validator node from genesis")
        .children(
          opt[String]('g', "genesis")
            .action((x, c) => c.copy(genesisPath = Path(x)))
            .text("Path to genesis file")
            .required()
        )
    )
  }

  def parse[F[_]: Async](args: List[String]): F[CliConfig] = {
    val argsHelp = if (args.isEmpty) {
      List("--help")
    } else args

    val setup: OParserSetup = new DefaultOParserSetup {
      override def showUsageOnError: Option[Boolean] = Some(true)
    }

    EitherT
      .fromEither[F] {
        OParser
          .parse(cliParser, argsHelp, CliConfig(), setup)
          .toRight(new RuntimeException("CLI params are missing"))
      }
      .rethrowT
  }

}
