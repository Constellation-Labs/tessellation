package org.tessellation.currency.l0

import java.util.UUID

import cats.effect.IO

import org.tessellation.BuildInfo
import org.tessellation.currency._
import org.tessellation.currency.schema.currency._
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.sdk.domain.rewards.Rewards
import org.tessellation.security.SecurityProvider

object Main
    extends CurrencyL0App(
      "Currency-l0",
      "Currency L0 node",
      ClusterId(UUID.fromString("517c3a05-9219-471b-a54c-21b7d72f4ae5")),
      version = BuildInfo.version
    ) {

  def dataApplication: Option[BaseDataApplicationL0Service[IO]] = None

  def rewards(implicit sp: SecurityProvider[IO]): Option[Rewards[
    IO,
    CurrencyTransaction,
    CurrencyBlock,
    CurrencySnapshotStateProof,
    CurrencyIncrementalSnapshot
  ]] = None
}
