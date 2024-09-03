package io.constellationnetwork.node.shared.cli

import cats.data.NonEmptySet
import cats.syntax.eq._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.env.AppEnvironment.Mainnet
import io.constellationnetwork.env.env._
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.peer.PeerId

import eu.timepit.refined.auto._
import fs2.io.file.Path

object CliMethod {
  val collateralConfig: (AppEnvironment, Option[Amount]) => CollateralConfig = (environment: AppEnvironment, amount: Option[Amount]) =>
    CollateralConfig(
      amount = amount
        .filter(_ => environment =!= Mainnet)
        .getOrElse(Amount(250_000_00000000L))
    )
}

trait CliMethod {

  val keyStore: StorePath
  val alias: KeyAlias
  val password: Password

  val environment: AppEnvironment

  val seedlistPath: Option[SeedListPath]

  val l0SeedlistPath: Option[SeedListPath]

  val prioritySeedlistPath: Option[SeedListPath]

  val stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]]

  val trustRatingsPath: Option[Path]

  val httpConfig: HttpConfig

  val collateralAmount: Option[Amount]

  def nodeSharedConfig(c: SharedConfigReader): SharedConfig = SharedConfig(
    environment,
    c.gossip,
    httpConfig,
    c.leavingDelay,
    c.stateAfterJoining,
    CliMethod.collateralConfig(environment, collateralAmount),
    c.trust.storage,
    c.priorityPeerIds.get(environment),
    c.snapshot.size,
    c.feeConfigs.get(environment).map(SortedMap.from(_)).getOrElse(SortedMap.empty),
    c.forkInfoStorage,
    c.lastKryoHashOrdinal
  )

}
