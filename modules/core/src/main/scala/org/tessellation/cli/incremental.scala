package org.tessellation.cli

import org.tessellation.ext.decline.decline._
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.AppEnvironment.{Mainnet, Testnet}

import com.monovore.decline.Opts
import com.monovore.decline.refined._
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

object incremental {
  val lastFullGlobalSnapshot: Map[AppEnvironment, SnapshotOrdinal] = Map(
    Mainnet -> SnapshotOrdinal(0L), // TODO: set before mainnet release
    Testnet -> SnapshotOrdinal(736766L)
  )

  val lastFullGlobalSnapshotOrdinalOpts: Opts[Option[SnapshotOrdinal]] = Opts
    .argument[NonNegLong]("lastFullGlobalSnapshotOrdinal")
    .map(SnapshotOrdinal(_))
    .orNone
}
