package org.tessellation.sdk.infrastructure.collateral

import cats.effect.{IO, Resource}

import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.schema.security.hex.Hex
import org.tessellation.sdk.config.types.CollateralConfig
import org.tessellation.sdk.domain.collateral.LatestBalances
import org.tessellation.sdk.infrastructure.Collateral

import eu.timepit.refined.auto._
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object CollateralSuite extends MutableIOSuite with Checkers {

  private val (address1, peer1) = (
    Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB"),
    PeerId(
      Hex(
        "6128e64d623ce4320c9523dc6d64d7d93647e40fb44c77d70bcb34dc4042e63cde16320f336c9c0011315aa9f006ad2941b9a92102a055e1bcc5a66ef8b612ef"
      )
    )
  )

  type Res = SecurityProvider[IO]

  override def sharedResource: Resource[IO, SecurityProvider[IO]] =
    SecurityProvider.forAsync[IO]

  def mkCollateral(balances: Option[Map[Address, Balance]], collateral: Amount)(implicit sc: SecurityProvider[IO]) = {
    val latestBalances = new LatestBalances[IO] {
      def getLatestBalances = IO.delay(balances)

      def getLatestBalancesStream = ???
    }
    Collateral.make[IO](CollateralConfig(collateral), latestBalances)
  }

  test("should return true when balances are not initialized yet") { implicit sc =>
    mkCollateral(None, Amount(25_000_000_000_000L))
      .hasCollateral(peer1)
      .map(expect.same(true, _))
  }

  test(
    "should return false when the balance for a given address is empty or not found and required collateral is bigger than 0"
  ) { implicit sc =>
    mkCollateral(Some(Map.empty), Amount(25_000_000_000_000L))
      .hasCollateral(peer1)
      .map(expect.same(false, _))
  }

  test(
    "should return true when the balance for a given address is empty or not found and required collateral is 0"
  ) { implicit sc =>
    mkCollateral(Some(Map.empty), Amount(0L))
      .hasCollateral(peer1)
      .map(expect.same(true, _))
  }

  test("should return true when the balance for a given address is equal to the required collateral") { implicit sc =>
    mkCollateral(Some(Map((address1, Balance(25_000_000_000_000L)))), Amount(25_000_000_000_000L))
      .hasCollateral(peer1)
      .map(expect.same(true, _))
  }

  test("should return false when the balance for a given address is lower than the required collateral") { implicit sc =>
    mkCollateral(Some(Map((address1, Balance(24_999_999_999_999L)))), Amount(25_000_000_000_000L))
      .hasCollateral(peer1)
      .map(expect.same(false, _))
  }
}
