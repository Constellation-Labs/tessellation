package org.tessellation.dag.l0.cli

import org.tessellation.env.AppEnvironment
import org.tessellation.env.AppEnvironment._
import org.tessellation.ext.decline.decline._
import org.tessellation.schema.SnapshotOrdinal

import com.monovore.decline.Opts
import com.monovore.decline.refined._
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

object incremental {
  val lastFullGlobalSnapshot: Map[AppEnvironment, SnapshotOrdinal] = Map(
    Mainnet -> SnapshotOrdinal(766717L),
    Testnet -> SnapshotOrdinal(736766L),
    Integrationnet -> SnapshotOrdinal(0L)
  )

  val lastFullGlobalSnapshotOrdinalOpts: Opts[Option[SnapshotOrdinal]] = Opts
    .argument[NonNegLong]("lastFullGlobalSnapshotOrdinal")
    .map(SnapshotOrdinal(_))
    .orNone
}
