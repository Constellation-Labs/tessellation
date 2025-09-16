package io.constellationnetwork.currency.cli

import cats.syntax.all._

import io.constellationnetwork.ext.decline.decline._

import com.monovore.decline._
import fs2.io.file.Path
import io.estatico.newtype.macros.newtype

object MetagraphOwnerMessageOpts {

  @newtype
  case class MetagraphOwnerMessagePath(value: Path)

  object MetagraphOwnerMessagePath {
    val opts: Opts[Option[MetagraphOwnerMessagePath]] =
      Opts
        .option[MetagraphOwnerMessagePath](
          "metagraph-owner-message",
          help = "Path to metagraph owner message"
        )
        .orNone

  }
}
