package io.constellationnetwork.node.shared.infrastructure.collateral

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.config.DelegatedRewardsEra
import io.constellationnetwork.node.shared.config.types.CollateralConfig
import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
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
  private val legacyCollateral = Amount(250_000_00000000L)
  private val era = DelegatedRewardsEra(EpochProgress(100L), SortedMap.empty)

  test("live DAG collateral uses finalized head epoch and preserves the pre-era requirement on replay") { implicit sc =>
    val balances = Map(address1 -> Balance(500_000_00000000L))
    for {
      state <- Ref.of[IO, Option[(EpochProgress, Map[Address, Balance])]](None)
      reads <- Ref.of[IO, Int](0)
      collateral = Collateral.makeForGlobalSnapshots[IO](CollateralConfig(legacyCollateral), reads.update(_ + 1) >> state.get, era)
      checks <- List(99L -> true, 100L -> false, 101L -> false, 99L -> true).traverse {
        case (head, expected) =>
          state.set(Some(EpochProgress(NonNegLong.unsafeFrom(head)) -> balances)) >> collateral.hasCollateral(peer1).map { actual =>
            expect.same(expected, actual)
          }
      }
      count <- reads.get
    } yield checks.reduce(_ and _).and(expect.same(4, count))
  }

  test("new-era operator collateral enforces 1M datum boundaries without counting missing balances") { implicit sc =>
    val minimum = DelegatedRewardsEra.MinimumNodeCollateral.value.value
    List(None -> false, Some(minimum - 1L) -> false, Some(minimum) -> true, Some(minimum + 1L) -> true).traverse {
      case (amount, expected) =>
        val balances = amount.map(value => address1 -> Balance(eu.timepit.refined.types.numeric.NonNegLong.unsafeFrom(value))).toMap
        Collateral
          .makeForGlobalSnapshots[IO](CollateralConfig(legacyCollateral), IO.pure(Some(EpochProgress(100L) -> balances)), era)
          .hasCollateral(peer1)
          .map(actual => expect.same(expected, actual))
    }.map(_.reduce(_ and _))
  }

  test("disabled policy retains configured collateral and missing head retains the existing startup behavior") { implicit sc =>
    for {
      disabled <- Collateral
        .makeForGlobalSnapshots[IO](
          CollateralConfig(legacyCollateral),
          IO.pure(Some(EpochProgress(1000L) -> Map(address1 -> Balance(legacyCollateral.value)))),
          DelegatedRewardsEra.Disabled
        )
        .hasCollateral(peer1)
      startup <- Collateral.makeForGlobalSnapshots[IO](CollateralConfig(legacyCollateral), IO.pure(None), era).hasCollateral(peer1)
    } yield expect.same(true, disabled).and(expect.same(true, startup))
  }

}
