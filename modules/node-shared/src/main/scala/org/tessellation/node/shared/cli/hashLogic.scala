package org.tessellation.node.shared.cli

import org.tessellation.env.AppEnvironment
import org.tessellation.env.AppEnvironment._
import org.tessellation.schema.SnapshotOrdinal

import com.monovore.decline.Opts
import com.monovore.decline.refined._
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

object hashLogic {
  // TODO: update ordinal before release on each environment
  val lastKryoHashOrdinal: Map[AppEnvironment, SnapshotOrdinal] = Map(
    Mainnet -> SnapshotOrdinal(0L),
    Testnet -> SnapshotOrdinal(0L),
    Integrationnet -> SnapshotOrdinal(0L)
  )

  val lastKryoHashOrdinalOpts: Opts[Option[SnapshotOrdinal]] =
    Opts
      .argument[NonNegLong]("lastKryoHashOrdinal")
      .map(SnapshotOrdinal(_))
      .orNone
}
