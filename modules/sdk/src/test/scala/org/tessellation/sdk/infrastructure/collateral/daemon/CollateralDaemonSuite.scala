package org.tessellation.sdk.infrastructure.collateral.daemon

import cats.effect.std.Supervisor
import cats.effect.testkit.TestControl
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.concurrent.duration._

import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.schema.generators._
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.schema.security.hex.Hex
import org.tessellation.sdk.config.types.CollateralConfig
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.collateral.LatestBalances
import org.tessellation.sdk.infrastructure.Collateral
import org.tessellation.sdk.infrastructure.cluster.storage.ClusterStorage

import eu.timepit.refined.auto._
import fs2.Stream
import fs2.concurrent.SignallingRef
import suite.ResourceSuite
import weaver.scalacheck.Checkers

object CollateralDaemonSuite extends ResourceSuite with Checkers {

  type Res = SecurityProvider[IO]

  override def sharedResource: Resource[IO, SecurityProvider[IO]] =
    SecurityProvider.forAsync[IO]

  private val (address1, peer1) = (
    Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB"),
    PeerId(
      Hex(
        "6128e64d623ce4320c9523dc6d64d7d93647e40fb44c77d70bcb34dc4042e63cde16320f336c9c0011315aa9f006ad2941b9a92102a055e1bcc5a66ef8b612ef"
      )
    )
  )

  private val (address2, peer2) = (
    Address("DAG07tqNLYW8jHU9emXcRTT3CfgCUoumwcLghopd"),
    PeerId(
      Hex(
        "79c4a78387a8782dbc88de95098d134a7dbf3b8a3316eaa1e41e112dc5b21a5b0cefdd0871495435591089264aa5c8a2429a75b384519662184bedfa6e7b886f"
      )
    )
  )

  def mkLatestBalances(balancesRef: SignallingRef[IO, Option[Map[Address, Balance]]]) =
    new LatestBalances[IO] {
      def getLatestBalances: F[Option[Map[Address, Balance]]] = balancesRef.get

      def getLatestBalancesStream: Stream[F, Map[Address, Balance]] =
        balancesRef.discrete.flatMap(_.fold[Stream[F, Map[Address, Balance]]](Stream.empty)(Stream(_)))
    }

  def mkClusterStorage() = {
    val peers = Map(peer1 -> peerGen.sample.get.copy(id = peer1), peer2 -> peerGen.sample.get.copy(id = peer2))
    ClusterStorage.make[IO](ClusterId("8d07c061-d42f-4d9c-9efc-37e0d1ee73e7"), peers)
  }

  def mkCollateralDaemon(latestBalances: LatestBalances[IO], clusterStorage: ClusterStorage[IO])(
    implicit sc: SecurityProvider[IO],
    S: Supervisor[IO]
  ) = {
    val collateralConfig = CollateralConfig(Amount(25_000_000_000_000L))
    val collateral = Collateral.make[IO](collateralConfig, latestBalances)
    CollateralDaemon.make(collateral, latestBalances, clusterStorage)
  }

  test("peers stay in cluster when collateral is sufficient") { implicit sp =>
    mkClusterStorage().flatMap { clusterStorage =>
      val prog =
        (
          SignallingRef
            .of[IO, Option[Map[Address, Balance]]](
              Some(Map(address1 -> Balance(25_000_000_000_000L), address2 -> Balance(25_000_000_000_000L)))
            )
            .asResource,
          Supervisor[IO]
        ).tupled.use {
          case (balancesRef, supervisor) =>
            implicit val _super = supervisor
            val latestBalances = mkLatestBalances(balancesRef)
            val collateralDaemon = mkCollateralDaemon(latestBalances, clusterStorage)
            collateralDaemon.start >> balancesRef.set(
              Map(address1 -> Balance(25_000_000_000_000L), address2 -> Balance(25_000_000_000_000L)).some
            )
        }

      TestControl.executeEmbed(prog) >> clusterStorage.getPeers.map(_.map(_.id)).map(ids => expect.same(ids, Set(peer1, peer2)))
    }

  }

  test("peers are removed from cluster when collateral is not sufficient") { implicit sp =>
    mkClusterStorage().flatMap { clusterStorage =>
      val prog =
        (
          SignallingRef
            .of[IO, Option[Map[Address, Balance]]](
              Some(Map(address1 -> Balance(25_000_000_000_000L), address2 -> Balance(25_000_000_000_000L)))
            )
            .asResource,
          Supervisor[IO]
        ).tupled.use {
          case (balancesRef, supervisor) =>
            implicit val _super = supervisor
            val latestBalances = mkLatestBalances(balancesRef)
            val collateralDaemon = mkCollateralDaemon(latestBalances, clusterStorage)
            collateralDaemon.start >> balancesRef.set(
              Map(address1 -> Balance(24_999_999_999_999L), address2 -> Balance(25_000_000_000_000L)).some
            ) >> IO.sleep(1.second)
        }

      TestControl.executeEmbed(prog) >> clusterStorage.getPeers.map(_.map(_.id)).map(ids => expect.same(ids, Set(peer2)))
    }
  }
}
