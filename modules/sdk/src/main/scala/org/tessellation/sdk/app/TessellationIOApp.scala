package org.tessellation.sdk.app

import cats.effect.{ExitCode, IO, Resource}

import org.tessellation.sdk.cli.CliMethod

import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp

abstract class TessellationIOApp
    extends CommandIOApp(
      name = "",
      header = "Tessellation Node",
      version = "0.0.x"
    ) {

  val opts: Opts[CliMethod]

  def run: Resource[IO, Unit]

  override final def main: Opts[IO[ExitCode]] =
    opts.map { _ =>
      (for {
        _ <- run
      } yield ()).useForever
    }

}
